package com.offlinepay.wallet

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicBoolean

private const val ZERO_ADDR = "0x0000000000000000000000000000000000000000"

/// Process-wide handles. Getters (not cached vals) for anything tied to the
/// wallet key, because Backup→Restore can swap the key at runtime.
object Wallet {
    lateinit var app: Context
        private set

    fun init(ctx: Context) { if (!::app.isInitialized) app = ctx.applicationContext }

    val keyVault: KeyVault get() = KeyVault(app)
    val me: String get() = keyVault.address.lowercase()
    val cache: BalanceCache get() = BalanceCache.get(app)
    val db: VoucherDb get() = VoucherDb.get(app)
    val store: VoucherStore get() = VoucherStore(app)
    val activity: ActivityStore get() = ActivityStore(app)
    val backend: BackendClient by lazy { BackendClient() }

    @Volatile private var chainCache: Pair<String, SettlementClient>? = null
    val chain: SettlementClient get() {
        val kv = keyVault
        chainCache?.let { if (it.first == kv.address) return it.second }
        return SettlementClient(Config.RPC_URL, Config.VAULT_ADDRESS, Config.CHAIN_ID, kv.keyPair, kv.address)
            .also { chainCache = kv.address to it }
    }

    /// Who the vault pays for this row: the signed recipient, or for a
    /// true-bearer card the merchant primary committed by the reader.
    fun payee(r: VoucherRow): String =
        (if (r.recipient.equals(ZERO_ADDR, true)) r.endorsementPrimary ?: ZERO_ADDR else r.recipient).lowercase()
}

/// Everything the UI shows, derived in one place from one snapshot.
///
///   balance  = onChain − sending + receiving + relayFeesPending
///   onChain  = locked + walletUsdc          (both from the same block)
///   sending  = vouchers we signed that the chain hasn't consumed yet
///   receiving= vouchers paid to us that the chain hasn't consumed yet
///
/// A voucher leaves `sending`/`receiving` at exactly the snapshot where the
/// chain reports it used — the same snapshot whose locked/usdc already
/// reflect it — so no amount is ever counted twice or dropped in between.
data class BalanceView(
    val balance: Long = 0L,
    val onChain: Long = 0L,
    val locked: Long = 0L,
    val walletUsdc: Long = 0L,
    val sending: Long = 0L,
    val receiving: Long = 0L,
    val relayFeesPending: Long = 0L,
    /// Signable right now with no network: vault funds not already promised.
    val sendableOffline: Long = 0L,
    /// Also counts wallet USDC, which gets locked on demand when online.
    val sendableOnline: Long = 0L,
    val maticWei: BigInteger = BigInteger.ZERO,
    val syncedAtMs: Long = 0L,
    val hasSnapshot: Boolean = false,
    val pendingReceiveCount: Int = 0,
)

object BalanceEngine {
    private const val TAG = "OfflinePay/Balance"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> Log.w("OfflinePay/Wallet", "background task failed", t) })
    private val _view = MutableStateFlow(BalanceView())
    val view: StateFlow<BalanceView> = _view.asStateFlow()
    // CONFLATED + single consumer: renders can never finish out of order
    // and overwrite a newer result with an older one.
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean(false)

    fun start(ctx: Context) {
        Wallet.init(ctx)
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            for (t in trigger) {
                runCatching { _view.value = compute() }.onFailure { Log.w(TAG, "compute failed", it) }
            }
        }
        scope.launch { Wallet.db.activityDao().recent().collect { invalidate() } }
        scope.launch { Wallet.db.voucherDao().recent().collect { invalidate() } }
        // Expiry is time-based; re-evaluate periodically even with no events.
        scope.launch { while (true) { delay(15_000); invalidate() } }
        invalidate()
    }

    fun invalidate() { trigger.trySend(Unit) }

    private suspend fun compute(): BalanceView {
        val cache = Wallet.cache
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000
        val used = cache.chainUsed()
        val self = cache.selfSettled()

        val locked = cache.locked.toLong()
        val usdc = cache.usdc.toLong()

        // A sent voucher still counts as "sending" until the chain consumes it
        // or it can no longer be settled (TTL + 1h grace), at which point the
        // locked funds are ours again.
        val sentWindowMs = (Config.DEFAULT_TTL_SECONDS + 3600) * 1000
        val sending = Wallet.db.activityDao().recentList()
            .filter { it.kind == "sent" && it.voucherId != null }
            .filter { it.voucherId!!.lowercase() !in used && nowMs - it.ts < sentWindowMs }
            .sumOf { it.amountBaseUnits }

        var receiving = 0L
        var pendingCount = 0
        for (r in Wallet.store.forRecipient(Wallet.me)) {
            val id = r.voucherId.lowercase()
            if (id in used) continue                       // already inside locked/usdc
            val settledElsewhere = r.status == "settled"
            if (!settledElsewhere && r.expiry <= nowSec) continue  // dead, never paid
            val amt = BigInteger(r.amount).toLong()
            // Settled but not in our snapshot yet: a relay (not us) keeps 2%.
            receiving += if (settledElsewhere && id !in self) amt - Config.settlerFee(amt) else amt
            if (!settledElsewhere) pendingCount++
        }

        val relayPending = cache.relayFees().filterKeys { it !in used }.values.sum()
        val onChain = locked + usdc
        val sendableOffline = (locked - sending).coerceAtLeast(0L)
        return BalanceView(
            balance = (onChain - sending).coerceAtLeast(0L) + receiving + relayPending,
            onChain = onChain,
            locked = locked,
            walletUsdc = usdc,
            sending = sending,
            receiving = receiving,
            relayFeesPending = relayPending,
            sendableOffline = sendableOffline,
            sendableOnline = sendableOffline + usdc,
            maticWei = cache.matic,
            syncedAtMs = cache.lastSyncedMs,
            hasSnapshot = cache.hasSnapshot,
            pendingReceiveCount = pendingCount,
        )
    }
}

/// Pulls one consistent chain snapshot from the backend.
object BalanceSync {
    private const val TAG = "OfflinePay/Sync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> Log.w("OfflinePay/Wallet", "background task failed", t) })
    private val mutex = Mutex()

    fun refreshAsync(ctx: Context) { scope.launch { refresh(ctx) } }

    /// Returns true if the backend answered. Serialized: a caller that
    /// arrives mid-refresh waits and then gets its own fresh read (it may
    /// have just broadcast a tx the in-flight read predates).
    suspend fun refresh(ctx: Context, autoLock: Boolean = true): Boolean {
        Wallet.init(ctx)
        if (!Net.isOnline(Wallet.app)) return false
        val ok = mutex.withLock {
            val resp = runCatching { Wallet.backend.walletState(Wallet.me, unresolvedIds()) }
                .onFailure { Log.w(TAG, "state fetch failed: ${it.message}") }
                .getOrNull() ?: return@withLock false
            Wallet.cache.applySnapshot(resp)
            markRowsUsedOnChain(resp)
            FeeLedger.reconcile()
            BalanceEngine.invalidate()
            true
        }
        if (ok && autoLock) AutoLocker.lockIfWorthIt()
        return ok
    }

    private suspend fun unresolvedIds(): List<String> {
        val cache = Wallet.cache
        val used = cache.chainUsed()
        val now = System.currentTimeMillis()
        val windowMs = (Config.DEFAULT_TTL_SECONDS + 3600) * 1000
        val sent = Wallet.db.activityDao().recentList()
            .filter { it.kind == "sent" && it.voucherId != null && now - it.ts < windowMs }
            .map { it.voucherId!!.lowercase() }
        val mine = Wallet.store.forRecipient(Wallet.me).map { it.voucherId.lowercase() }
        val holding = Wallet.store.pendingForSettle().map { it.voucherId.lowercase() }
        val relayed = cache.relayFees().keys
        return (sent + mine + holding + relayed).distinct().filter { it !in used }.take(100)
    }

    /// Any row we still hold as accepted/replica that the chain says is used
    /// was settled by someone else — stop trying to settle it.
    private suspend fun markRowsUsedOnChain(resp: BackendClient.StateResp) {
        val used = resp.used.filterValues { it }.keys
        if (used.isEmpty()) return
        val store = Wallet.store
        for (r in store.pendingForSettle()) {
            if (r.voucherId.lowercase() in used) store.markSettled(r.voucherId, "")
        }
    }
}

/// Writes one "Relay fee (2%)" ledger row for each voucher paid to us that
/// someone else broadcast, so the activity list reconciles with the balance.
object FeeLedger {
    private val mutex = Mutex()

    suspend fun reconcile() = mutex.withLock {
        val cache = Wallet.cache
        val used = cache.chainUsed()
        val self = cache.selfSettled()
        for (r in Wallet.store.forRecipient(Wallet.me)) {
            val id = r.voucherId.lowercase()
            val settled = r.status == "settled" || id in used
            if (!settled || id in self) continue
            if (!cache.markFeePaidRecorded(id)) continue
            Wallet.activity.recordFeePaid(r.voucherId, Config.settlerFee(BigInteger(r.amount).toLong()), r.settledTx)
        }
    }
}

/// Moves wallet USDC (received payments, relay rewards) into the vault so it
/// can be spent offline. Two txs (approve + lock), so only above a dust floor.
object AutoLocker {
    private const val TAG = "OfflinePay/AutoLock"
    private const val FAILURE_BACKOFF_MS = 60_000L
    private val mutex = Mutex()
    @Volatile private var lastFailureMs = 0L

    /// Background path: skip if busy, recently failed, or not worth the gas.
    suspend fun lockIfWorthIt() {
        if (!Net.isOnline(Wallet.app)) return
        if (System.currentTimeMillis() - lastFailureMs < FAILURE_BACKOFF_MS) return
        if (!mutex.tryLock()) return
        try {
            val usdc = Wallet.cache.usdc
            if (usdc < BigInteger.valueOf(Config.AUTOLOCK_MIN_BASE_UNITS)) return
            lockNow(usdc)
        } catch (t: Throwable) {
            lastFailureMs = System.currentTimeMillis()
            Log.w(TAG, "auto-lock failed: ${t.message}")
        } finally {
            mutex.unlock()
        }
    }

    /// Foreground path (top-up, send): lock everything currently in the
    /// wallet. Waits for any background lock to finish first. Returns the
    /// lock tx, or null if there was nothing left to lock.
    suspend fun lockAll(ctx: Context): String? {
        Wallet.init(ctx)
        return mutex.withLock {
            BalanceSync.refresh(ctx, autoLock = false)
            val usdc = Wallet.cache.usdc
            if (usdc.signum() <= 0) null else lockNow(usdc)
        }
    }

    private suspend fun lockNow(amount: BigInteger): String {
        ensureGas()
        Log.i(TAG, "locking $amount into vault")
        val tx = Wallet.chain.approveAndLock(Config.USDC_ADDRESS, amount)
        BalanceSync.refresh(Wallet.app, autoLock = false)
        return tx
    }

    suspend fun ensureGas() {
        if (Wallet.cache.matic >= Config.GAS_FLOOR_WEI) return
        val r = Wallet.backend.init(Wallet.me, 0L)
        if (!r.ok) error(r.error ?: "gas top-up failed")
        BalanceSync.refresh(Wallet.app, autoLock = false)
    }
}
