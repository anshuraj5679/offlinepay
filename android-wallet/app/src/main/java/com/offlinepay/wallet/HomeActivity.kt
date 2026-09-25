package com.offlinepay.wallet

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.offlinepay.wallet.ui.DashState
import com.offlinepay.wallet.ui.DashboardScreen
import com.offlinepay.wallet.ui.RecentRow
import com.offlinepay.wallet.ui.setOffpayContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.math.BigInteger

/// Dashboard. Pure view over BalanceEngine / ActivityStore / mesh state —
/// balance math, chain sync and settlement all live in the app-wide
/// singletons so every screen shows the same numbers.
class HomeActivity : ComponentActivity() {
    private lateinit var activity: ActivityStore
    private lateinit var received: VoucherStore
    private lateinit var espBondStore: EspBondStore

    private val state = MutableStateFlow(DashState())

    private val meshPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { WalletMesh.restart(this) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        activity = ActivityStore(this)
        received = VoucherStore(this)
        espBondStore = EspBondStore(this)
        state.value = state.value.copy(walletAddress = KeyVault(this).address)

        setOffpayContent {
            val s by state.collectAsState()
            DashboardScreen(
                state = s,
                onSend     = { startActivity(Intent(this, SendActivity::class.java)) },
                onReceive  = { startActivity(Intent(this, ReceiveActivity::class.java)) },
                onTopup    = { startActivity(Intent(this, TopupActivity::class.java)) },
                onHistory  = { startActivity(Intent(this, HistoryActivity::class.java)) },
                onBackup   = { startActivity(Intent(this, BackupRestoreActivity::class.java)) },
                onSettleNow= { Settler.kick(this, force = true) },
                onAddressClick = {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse(Config.addressUrl(KeyVault(this).address))))
                },
                onEsp = { startActivity(Intent(this, EspActivity::class.java)) },
                onFixMesh = {
                    MeshPermissions.blocker(this)?.let { b ->
                        runCatching { startActivity(MeshPermissions.fixIntent(this, b)) }
                    }
                },
            )
        }
        // Location/Bluetooth toggles and permission changes happen outside
        // the app; poll cheaply and re-arm the mesh the moment it's unblocked.
        lifecycleScope.launch {
            var wasBlocked = false
            while (true) {
                val b = MeshPermissions.blocker(this@HomeActivity)
                if (wasBlocked && b == null) WalletMesh.restart(this@HomeActivity)
                wasBlocked = b != null
                state.value = state.value.copy(meshBlockedReason = b?.message)
                kotlinx.coroutines.delay(3_000)
            }
        }

        lifecycleScope.launch {
            BalanceEngine.view.collect { v ->
                state.value = state.value.copy(
                    spendableUsdc = usd(v.balance),
                    lockedUsdc    = usd(v.onChain),
                    inFlightUsdc  = usd(v.sending),
                    receivingUsdc = usd(v.receiving),
                    pendingCount  = v.pendingReceiveCount,
                    pendingUsdc   = usd(v.receiving),
                    syncedSecondsAgo = syncedAgo(v.syncedAtMs),
                )
            }
        }
        // The view only re-emits when numbers change; keep "synced Xs ago" honest.
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5_000)
                state.value = state.value.copy(syncedSecondsAgo = syncedAgo(BalanceEngine.view.value.syncedAtMs))
            }
        }
        lifecycleScope.launch {
            activity.recent().collect { rows ->
                state.value = state.value.copy(recent = rows.take(20).map { it.toRecentRow() })
            }
        }
        lifecycleScope.launch {
            Settler.status.collect { st -> state.value = state.value.copy(settleStatus = st) }
        }
        // Re-offer every unsettled voucher paid to us to mesh peers, so a peer
        // that joins later can still relay it. broadcast() is a no-op once a
        // peer has ACKed that voucher.
        lifecycleScope.launch {
            received.recent().collect { rows ->
                for (row in rows) {
                    if (row.status != "accepted") continue
                    val v = Voucher(
                        voucherId = row.voucherId, payer = row.payer,
                        merchant = row.merchant, recipient = row.recipient,
                        amount = BigInteger(row.amount),
                        expiry = row.expiry, nonce = row.nonce, signature = row.signature,
                        cardUid = row.cardUid,
                    )
                    val endorsement = if (
                        row.endorsementSig != null && row.endorsementPrimary != null &&
                        row.endorsementDevice != null && row.endorsementTs != null
                    ) Endorsement(
                        timestamp        = row.endorsementTs,
                        merchantPrimary  = row.endorsementPrimary,
                        deviceAddress    = row.endorsementDevice,
                        signature        = row.endorsementSig,
                    ) else null
                    WalletMesh.broadcast(v, endorsement)
                }
            }
        }
        lifecycleScope.launch {
            WalletMesh.peerCountFlow.collect { n -> state.value = state.value.copy(meshPeerCount = n) }
        }
        lifecycleScope.launch {
            WalletMesh.events.collect { ev -> showMeshToast(ev) }
        }
        lifecycleScope.launch {
            espBondStore.stateFlow.collect { bond ->
                state.value = state.value.copy(
                    espPairedAddress = bond.espAddress,
                    espLastSeenMs    = bond.lastSeenMs,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WalletMesh.acquire(this)
        val ask = MeshPermissions.toRequest(this)
        if (ask.isNotEmpty()) meshPermLauncher.launch(ask.toTypedArray())
        BalanceSync.refreshAsync(this)
        Settler.kick(this)
    }

    override fun onPause() {
        WalletMesh.release()
        super.onPause()
    }

    private fun showMeshToast(ev: MeshEvent) {
        val text = when (ev) {
            is MeshEvent.PeerConnected     -> if (ev.total == 1) "🔗 mesh peer connected" else null
            is MeshEvent.ReplicaStored     -> "📥 voucher arrived via mesh"
            is MeshEvent.PaymentReceived   -> "💰 received \$${Money.fmt(ev.amount)} from ${ev.payer.take(6)}…${ev.payer.takeLast(4)}"
            is MeshEvent.SettledByPeer     -> "✓ relay settled — tx ${ev.txHash.take(10)}…"
            is MeshEvent.AdvertiseFailed   -> "mesh advertise failed: ${ev.reason}"
            else -> null
        } ?: return
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}

private fun usd(baseUnits: Long): String = "%.2f".format(java.util.Locale.US, baseUnits / 1e6)

private fun syncedAgo(syncedAtMs: Long): Int? =
    if (syncedAtMs == 0L) null
    else ((System.currentTimeMillis() - syncedAtMs) / 1000).toInt().coerceAtLeast(0)

private fun ActivityRow.toRecentRow(): RecentRow {
    val amt = "%.2f".format(java.util.Locale.US, amountBaseUnits.toDouble() / 1e6)
    val ts = relativeTs(ts)
    val explorer = txHash?.takeIf { it.isNotEmpty() }?.let { Config.txUrl(it) }
    val short = counterparty?.let { it.take(6) + "…" + it.takeLast(4) }
    return when (kind) {
        "topup"    -> RecentRow(
            title = "Top-up",
            sub = "ON-CHAIN · $ts",
            amountSigned = "+ \$$amt",
            incoming = true,
            explorerUrl = explorer,
        )
        "sent"     -> RecentRow(
            title = "Sent to ${short ?: "—"}",
            sub = "TAPPED · $ts",
            amountSigned = "− \$$amt",
            incoming = false,
            explorerUrl = counterparty?.let { Config.addressUrl(it) },
        )
        "received" -> RecentRow(
            title = "Received from ${short ?: "—"}",
            sub = "TAPPED · $ts",
            amountSigned = "+ \$$amt",
            incoming = true,
            explorerUrl = counterparty?.let { Config.addressUrl(it) },
        )
        "fee_earned" -> RecentRow(
            title = "Relay reward",
            sub = "SETTLED FOR ${short ?: "PEER"} · $ts",
            amountSigned = "+ \$$amt",
            incoming = true,
            explorerUrl = explorer,
        )
        "fee_paid" -> RecentRow(
            title = "Relay fee (2%)",
            sub = "PAID TO SETTLING PHONE · $ts",
            amountSigned = "− \$$amt",
            incoming = false,
            explorerUrl = explorer,
        )
        "settled"  -> RecentRow(
            title = note ?: "Settled on chain",
            sub = "TX ${txHash?.take(10) ?: ""}… · $ts",
            amountSigned = "⛓",
            incoming = true,
            explorerUrl = explorer,
        )
        else       -> RecentRow(
            title = note ?: kind,
            sub = ts.uppercase(),
            amountSigned = "",
            incoming = false,
        )
    }
}

private fun relativeTs(ms: Long): String {
    val now = System.currentTimeMillis()
    val diffSec = ((now - ms) / 1000).coerceAtLeast(0)
    return when {
        diffSec < 60     -> "JUST NOW"
        diffSec < 3600   -> "${diffSec / 60} MIN AGO"
        diffSec < 86400  -> "${diffSec / 3600} H AGO"
        diffSec < 604800 -> "${diffSec / 86400} D AGO"
        else             -> "EARLIER"
    }
}
