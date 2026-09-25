package com.offlinepay.wallet

import android.content.Context
import android.content.SharedPreferences
import java.math.BigInteger

/// Persistent wallet state for the balance engine.
///
/// The chain snapshot (locked / usdc / matic) is only ever replaced by a
/// NEWER block's snapshot, and `chainUsed` only ever contains ids the chain
/// itself reported as used. Mesh "settled" news never touches these — mixing
/// peer gossip into chain math is what made balances jump before.
class BalanceCache private constructor(ctx: Context) {

    private val prefs: SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    val snapshotBlock: Long get() = prefs.getLong(K_BLOCK, 0L)
    val locked: BigInteger get() = big(K_LOCKED)
    val usdc: BigInteger get() = big(K_USDC)
    val matic: BigInteger get() = big(K_MATIC)
    val lastSyncedMs: Long get() = prefs.getLong(K_SYNC_TS, 0L)
    val hasSnapshot: Boolean get() = prefs.contains(K_BLOCK)

    /// Apply a backend snapshot unless we already hold a newer block.
    fun applySnapshot(s: BackendClient.StateResp): Boolean = synchronized(lock) {
        if (!s.ok) return false
        val newlyUsed = s.used.filterValues { it }.keys.map { it.lowercase() }
        if (newlyUsed.isNotEmpty()) addAll(K_CHAIN_USED, newlyUsed)
        if (hasSnapshot && s.blockNumber < snapshotBlock) return false
        prefs.edit()
            .putLong(K_BLOCK, s.blockNumber)
            .putString(K_LOCKED, s.locked)
            .putString(K_USDC, s.usdc)
            .putString(K_MATIC, s.matic)
            .putLong(K_SYNC_TS, System.currentTimeMillis())
            .apply()
        true
    }

    fun chainUsed(): Set<String> = set(K_CHAIN_USED)
    fun isChainUsed(id: String) = id.lowercase() in chainUsed()

    /// Vouchers addressed to us that WE broadcast (msg.sender == recipient),
    /// so the contract took no relay fee. Marked before the tx is sent and
    /// removed again if the send fails.
    fun selfSettled(): Set<String> = set(K_SELF)
    fun markSelfSettled(ids: Collection<String>) = synchronized(lock) { addAll(K_SELF, ids.map { it.lowercase() }) }
    fun unmarkSelfSettled(ids: Collection<String>) = synchronized(lock) { removeAll(K_SELF, ids.map { it.lowercase() }) }

    /// Relay fees we earned by settling other people's vouchers: id → fee.
    fun relayFees(): Map<String, Long> = set(K_RELAY_FEES).mapNotNull { e ->
        val i = e.lastIndexOf(':')
        if (i <= 0) null else e.substring(0, i) to (e.substring(i + 1).toLongOrNull() ?: return@mapNotNull null)
    }.toMap()
    fun addRelayFee(id: String, fee: Long) = synchronized(lock) { addAll(K_RELAY_FEES, listOf("${id.lowercase()}:$fee")) }

    /// Returns true exactly once per id — guards the "relay fee paid" ledger row.
    fun markFeePaidRecorded(id: String): Boolean = synchronized(lock) {
        val cur = set(K_FEE_PAID)
        if (id.lowercase() in cur) false else { addAll(K_FEE_PAID, listOf(id.lowercase())); true }
    }

    private fun big(k: String): BigInteger =
        prefs.getString(k, null)?.let { runCatching { BigInteger(it) }.getOrNull() } ?: BigInteger.ZERO

    private fun set(k: String): Set<String> = prefs.getStringSet(k, null) ?: emptySet()

    // SharedPreferences string sets must be replaced, never mutated in place.
    private fun addAll(k: String, items: Collection<String>) {
        val cur = set(k)
        if (cur.containsAll(items)) return
        prefs.edit().putStringSet(k, HashSet(cur).apply { addAll(items) }).apply()
    }

    private fun removeAll(k: String, items: Collection<String>) {
        val cur = set(k)
        if (items.none { it in cur }) return
        prefs.edit().putStringSet(k, HashSet(cur).apply { removeAll(items.toSet()) }).apply()
    }

    companion object {
        // New file name: state written by older builds (other vault) must not leak in.
        private const val PREFS = "offpay_wallet_state_v2"
        private const val K_BLOCK = "snapBlock"
        private const val K_LOCKED = "locked"
        private const val K_USDC = "usdc"
        private const val K_MATIC = "matic"
        private const val K_SYNC_TS = "syncedAt"
        private const val K_CHAIN_USED = "chainUsed"
        private const val K_SELF = "selfSettled"
        private const val K_RELAY_FEES = "relayFees"
        private const val K_FEE_PAID = "feePaidRecorded"

        @Volatile private var INSTANCE: BalanceCache? = null
        fun get(ctx: Context): BalanceCache =
            INSTANCE ?: synchronized(this) { INSTANCE ?: BalanceCache(ctx).also { INSTANCE = it } }
    }
}
