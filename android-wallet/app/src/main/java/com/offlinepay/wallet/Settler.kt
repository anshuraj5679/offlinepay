package com.offlinepay.wallet

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigInteger

/// The one place vouchers get pushed on chain, whichever screen asks.
///
/// Roles:
///  - vouchers paid to us (accepted) → settle as soon as we're online.
///    We're msg.sender == recipient, so no fee is taken.
///  - vouchers we hold for others (replica) → we're the relay. Wait a short
///    grace period so an online recipient can settle their own fee-free;
///    if it's still unsettled after that, we settle and earn the 2%.
///
/// Before paying gas we ask the chain (one backend call) which vouchers are
/// already used and whether each payer still has the funds, and dry-run the
/// batch. A single already-used voucher would otherwise revert the entire
/// batch — the old "payment reverted" error.
object Settler {
    private const val TAG = "OfflinePay/Settler"
    private const val ZERO = "0x0000000000000000000000000000000000000000"
    private const val RELAY_GRACE_MS = 8_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> Log.w("OfflinePay/Wallet", "background task failed", t) })
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()
    @Volatile private var retryJob: Job? = null

    fun kick(ctx: Context, force: Boolean = false) {
        Wallet.init(ctx)
        scope.launch { run(ctx, force) }
    }

    suspend fun run(ctx: Context, force: Boolean = false) {
        Wallet.init(ctx)
        val ran = SettleGate.runIfFree {
            try { runInner(force) } catch (t: Throwable) {
                Log.w(TAG, "settle pass failed: ${t.message}")
                _status.value = Errors.friendly(t)
            }
        }
        if (!ran) Log.d(TAG, "settle already in flight")
    }

    private fun scheduleRetry(afterMs: Long) {
        retryJob?.cancel()
        retryJob = scope.launch { delay(afterMs); run(Wallet.app) }
    }

    private suspend fun runInner(force: Boolean) {
        val store = Wallet.store
        val activity = Wallet.activity
        val me = Wallet.me
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000

        val candidates = mutableListOf<VoucherRow>()
        for (r in store.pendingForSettle()) {
            when {
                !r.merchant.equals(ZERO, true) -> store.markRejected(r.voucherId, "NOT_BEARER")
                r.expiry <= nowSec -> store.markRejected(r.voucherId, "EXPIRED")
                r.recipient.equals(ZERO, true) && r.endorsementSig == null -> {
                    store.markRejected(r.voucherId, "BEARER_NEEDS_ENDORSEMENT")
                    activity.recordFailed("Bearer voucher needs reader-tap to settle")
                }
                else -> candidates += r
            }
        }
        // Nothing left to settle — drop any stale "offline — N will settle"
        // line (e.g. a relay settled it and told us over the mesh).
        if (candidates.isEmpty()) { _status.value = null; return }

        val (ready, waiting) = candidates.partition { r ->
            force || Wallet.payee(r) == me || nowMs - r.acceptedAtMs >= RELAY_GRACE_MS
        }
        if (waiting.isNotEmpty()) {
            scheduleRetry(waiting.minOf { RELAY_GRACE_MS - (nowMs - it.acceptedAtMs) }.coerceAtLeast(500L))
        }
        if (ready.isEmpty()) return

        if (!Net.isOnline(Wallet.app)) {
            _status.value = "offline — ${ready.size} will settle when online"
            return
        }

        val state = runCatching {
            Wallet.backend.walletState(me, ready.map { it.voucherId }, ready.map { it.payer })
        }.getOrElse {
            Log.w(TAG, "state fetch failed: ${it.message}")
            _status.value = "network issue — will retry"
            return
        }
        Wallet.cache.applySnapshot(state)

        // Settled by someone else already.
        val live = ready.filter { r ->
            val used = state.used[r.voucherId.lowercase()] == true
            if (used) store.markSettled(r.voucherId, "")
            !used
        }

        // Payer must still cover every voucher we're about to submit.
        val remaining = state.payerLocked.mapValues { BigInteger(it.value) }.toMutableMap()
        val funded = mutableListOf<VoucherRow>()
        for (r in live.sortedBy { it.acceptedAtMs }) {
            val payer = r.payer.lowercase()
            val amt = BigInteger(r.amount)
            val have = remaining[payer] ?: BigInteger.ZERO
            if (have < amt) {
                store.markRejected(r.voucherId, "INSUFFICIENT_LOCKED")
                activity.recordFailed("Sender ${short(payer)} has no locked funds — voucher dropped")
                continue
            }
            remaining[payer] = have - amt
            funded += r
        }
        if (funded.isEmpty()) { finish(); return }

        runCatching { AutoLocker.ensureGas() }.onFailure { Log.w(TAG, "gas top-up failed: ${it.message}") }

        _status.value = "settling ${funded.size} on chain…"
        var settledCount = 0
        for (r in funded.filter { it.recipient.equals(ZERO, true) }) {
            if (settleEndorsed(r)) settledCount++
        }
        val bound = funded.filter { !it.recipient.equals(ZERO, true) }
        if (bound.isNotEmpty()) settledCount += settleBound(bound)
        if (settledCount > 0) _status.value = "⛓ settled $settledCount on chain"
        finish()
    }

    private suspend fun finish() {
        BalanceSync.refresh(Wallet.app)
        BalanceEngine.invalidate()
    }

    /// Dry-run the batch; if anything in it would revert, drop exactly the
    /// offending vouchers (each checked alone) and submit the rest.
    private suspend fun settleBound(rows: List<VoucherRow>): Int {
        val chain = Wallet.chain
        val batch = if (chain.preflightBearerBatch(rows) == null) rows else {
            rows.filter { r ->
                val reason = chain.preflightBearerBatch(listOf(r))
                if (reason != null) handleRevert(r, reason)
                reason == null
            }
        }
        if (batch.isEmpty()) return 0
        return if (submit(batch) { chain.settleBearerBatch(batch) }) batch.size else 0
    }

    private suspend fun settleEndorsed(r: VoucherRow): Boolean {
        val reason = Wallet.chain.preflightBearerWithEndorsement(r)
        if (reason != null) { handleRevert(r, reason); return false }
        return submit(listOf(r)) { Wallet.chain.settleBearerWithEndorsement(r) }
    }

    private suspend fun submit(rows: List<VoucherRow>, send: suspend () -> String): Boolean {
        val store = Wallet.store
        val cache = Wallet.cache
        val me = Wallet.me
        // Mark BEFORE broadcasting: if we crash after the tx lands, FeeLedger
        // must not book a relay fee on a voucher we settled ourselves.
        val ownIds = rows.filter { Wallet.payee(it) == me }.map { it.voucherId }
        cache.markSelfSettled(ownIds)
        val tx = try {
            send()
        } catch (t: Throwable) {
            cache.unmarkSelfSettled(ownIds)
            Log.w(TAG, "settle failed: ${t.message}")
            return recoverFromFailure(rows, t)
        }
        for (r in rows) {
            store.markSettled(r.voucherId, tx)
            WalletMesh.broadcastSettled(r.voucherId, tx)
            val payee = Wallet.payee(r)
            if (payee != me) {
                val fee = Config.settlerFee(BigInteger(r.amount).toLong())
                cache.addRelayFee(r.voucherId, fee)
                Wallet.activity.recordFeeEarned(r.voucherId, payee, fee, tx)
            }
        }
        Wallet.activity.recordSettled(rows.map { it.voucherId }, tx)
        Log.i(TAG, "settled ${rows.size} tx=$tx")
        return true
    }

    /// No gas → let the backend relay (it becomes msg.sender and keeps the
    /// fee). Lost a race → the chain says used, which is success for the
    /// payee. Anything else stays pending for the next pass — never reject
    /// a voucher on a transient error, that would silently erase money.
    private suspend fun recoverFromFailure(rows: List<VoucherRow>, t: Throwable): Boolean {
        val msg = t.message.orEmpty()
        if ("insufficient funds" in msg) {
            val relayable = rows.filter { !it.recipient.equals(ZERO, true) }
            if (relayable.isNotEmpty()) {
                val resp = runCatching {
                    Wallet.backend.redeem(relayable.map {
                        BackendClient.RedeemItem(
                            BackendClient.VoucherFields(
                                payer = it.payer, merchant = it.merchant, recipient = it.recipient,
                                amount = it.amount, expiry = it.expiry, nonce = it.nonce, voucherId = it.voucherId,
                            ),
                            it.signature,
                        )
                    })
                }.getOrNull()
                val tx = resp?.tx
                if (resp?.ok == true && tx != null) {
                    val rejected = resp.rejected.map { it.voucherId.lowercase() }.toSet()
                    for (r in relayable) if (r.voucherId.lowercase() !in rejected) {
                        Wallet.store.markSettled(r.voucherId, tx)
                        WalletMesh.broadcastSettled(r.voucherId, tx)
                    }
                    Wallet.activity.recordSettled(relayable.map { it.voucherId }, tx)
                    return true
                }
            }
        }
        val state = runCatching {
            Wallet.backend.walletState(Wallet.me, rows.map { it.voucherId })
        }.getOrNull()
        if (state != null) {
            Wallet.cache.applySnapshot(state)
            val usedNow = rows.filter { state.used[it.voucherId.lowercase()] == true }
            usedNow.forEach { Wallet.store.markSettled(it.voucherId, "") }
            if (usedNow.size == rows.size) return true
        }
        _status.value = Errors.friendly(t)
        return false
    }

    /// Deterministic contract rejections → the voucher can never settle.
    private suspend fun handleRevert(r: VoucherRow, reason: String) {
        val store = Wallet.store
        when {
            "already settled" in reason -> store.markSettled(r.voucherId, "")
            "insufficient locked" in reason || "expired" in reason ||
            "bad signature" in reason || "bad amount" in reason || "not bearer" in reason ||
            "recipient=0" in reason || "bad voucher sig" in reason || "bad endorsement" in reason -> {
                store.markRejected(r.voucherId, Errors.rejectTag(RuntimeException(reason)).takeIf { it != "ERROR" } ?: "PREFLIGHT_FAIL")
                Wallet.activity.recordFailed("Can't settle ${short(r.voucherId)}: $reason")
            }
            else -> Log.w(TAG, "preflight ${short(r.voucherId)} failed, will retry: $reason")
        }
    }

    private fun short(s: String) = if (s.length > 12) s.take(6) + "…" + s.takeLast(4) else s
}
