package com.offlinepay.wallet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.offlinepay.wallet.ui.ReceiveScreen
import com.offlinepay.wallet.ui.ReceiveState
import com.offlinepay.wallet.ui.RecentRow
import com.offlinepay.wallet.ui.StatusKind
import com.offlinepay.wallet.ui.setOffpayContent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.math.BigInteger

private val walletScope = CoroutineScope(
    SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, t -> Log.w("OfflinePay/Receive", "receive task failed", t) }
)

class ReceiveActivity : ComponentActivity() {
    private lateinit var keyVault: KeyVault
    private lateinit var store: VoucherStore
    private lateinit var verifier: VoucherVerifier
    private lateinit var activity: ActivityStore
    private lateinit var reader: ReaderModeLoop
    private var btBridge: BluetoothBridge? = null

    private val state = MutableStateFlow(
        ReceiveState(walletAddress = "", status = "scanning… tap a sender phone to your back")
    )

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val qr = res.data?.getStringExtra("qr") ?: return@registerForActivityResult
            walletScope.launch { handleIncoming(qr); Settler.run(this@ReceiveActivity) }
        }
    }
    private val camPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launcher.launch(Intent(this, QrScanActivity::class.java))
        else state.value = state.value.copy(status = "camera permission denied", statusKind = StatusKind.Error)
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        keyVault = KeyVault(this)
        store    = VoucherStore(this)
        // Accepts only vouchers whose signed recipient is this wallet.
        verifier = VoucherVerifier(
            Config.CHAIN_ID, Config.VAULT_ADDRESS, Config.MAX_SINGLE_USDC,
            store, expectedRecipient = keyVault.address,
        )
        activity = ActivityStore(this)
        state.value = state.value.copy(walletAddress = keyVault.address)

        reader = ReaderModeLoop(
            this,
            ourAddressHex = keyVault.address,
            onVoucher = { json ->
                walletScope.launch {
                    handleIncoming(json)
                    Settler.run(this@ReceiveActivity)
                }
            },
            onError = { msg ->
                // A phone without NFC can still take card payments through the
                // paired ESP32 reader — don't greet the merchant with an error.
                state.value = if (btBridge != null && android.nfc.NfcAdapter.getDefaultAdapter(this) == null)
                    state.value.copy(status = "ready · customer taps their card on the reader",
                        statusKind = StatusKind.Idle)
                else state.value.copy(status = msg, statusKind = StatusKind.Error)
            }
        )

        btBridge = try {
            BluetoothBridge(this, scope = lifecycleScope, bondStore = EspBondStore(this)).also { bt ->
                lifecycleScope.launch {
                    bt.incoming.collect { iv ->
                        walletScope.launch { handleIncomingVoucher(iv.voucher, iv.endorsement) }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BT bridge init skipped: ${t.message}")
            null
        }

        val qrBitmap = com.offlinepay.wallet.Qr.render(keyVault.address, size = 600)

        setOffpayContent {
            val s by state.collectAsState()
            ReceiveScreen(
                state = s,
                qrBitmap = qrBitmap,
                onSettleNow = { Settler.kick(this, force = true) },
                onScanQr = {
                    val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                    if (granted) launcher.launch(Intent(this, QrScanActivity::class.java))
                    else camPermLauncher.launch(Manifest.permission.CAMERA)
                },
                onClose = { finish() },
            )
        }

        // Only vouchers that pay THIS wallet. Rows we hold as a relay for
        // other people used to show up here as "rejected" with an amount.
        val me = keyVault.address.lowercase()
        lifecycleScope.launch {
            store.recent().collect { rows ->
                val mine = rows.filter { Wallet.payee(it) == me }
                val mapped = mine.take(10).map { r ->
                    val amt = Money.fmt(BigInteger(r.amount).toLong())
                    val from = r.payer.take(6) + "…" + r.payer.takeLast(4)
                    val tx = r.settledTx?.takeIf { it.isNotEmpty() }
                    when (r.status) {
                        "settled"  -> RecentRow(
                            "Settled · from $from",
                            "ON CHAIN" + (tx?.let { " · TX ${it.take(10)}…" } ?: ""),
                            "+ \$$amt", true, explorerUrl = tx?.let { Config.txUrl(it) },
                        )
                        "accepted" -> RecentRow(
                            "Received · from $from",
                            "OFFLINE · PENDING SETTLE",
                            "+ \$$amt", true, explorerUrl = null,
                        )
                        else       -> RecentRow(
                            "Rejected · from $from",
                            "NOT PAID · ${r.rejectReason ?: ""}",
                            "  \$$amt", false, explorerUrl = null,
                        )
                    }
                }
                state.value = state.value.copy(
                    recent = mapped,
                    pendingCount = mine.count { it.status == "accepted" },
                )
            }
        }
        lifecycleScope.launch {
            WalletMesh.peerCountFlow.collect { n ->
                state.value = state.value.copy(meshPeerCount = n)
            }
        }
        lifecycleScope.launch {
            WalletMesh.events.collect { ev ->
                showMeshToast(ev)
                if (ev is MeshEvent.PaymentReceived) {
                    state.value = state.value.copy(
                        status = "✓ received \$${Money.fmt(ev.amount)} from ${ev.payer.take(6)}…${ev.payer.takeLast(4)} (via QR/mesh)",
                        statusKind = StatusKind.Success,
                    )
                }
                if (ev is MeshEvent.SettledByPeer) {
                    state.value = state.value.copy(
                        status = "⛓ settled by relay — tx ${ev.txHash.take(10)}…",
                        statusKind = StatusKind.Success,
                    )
                }
            }
        }
        // Surface settle progress; skip the replayed current value so an old
        // message from another screen doesn't overwrite "scanning…".
        lifecycleScope.launch {
            Settler.status.drop(1).collect { st ->
                if (st != null) state.value = state.value.copy(
                    status = st,
                    statusKind = if (st.startsWith("⛓")) StatusKind.Success else StatusKind.Working,
                )
            }
        }
    }

    private fun showMeshToast(ev: MeshEvent) {
        val text = when (ev) {
            is MeshEvent.PeerConnected     -> if (ev.total == 1) "🔗 mesh peer in range" else null
            is MeshEvent.VoucherBroadcast  -> if (ev.peerCount > 0)
                "📤 voucher shared with ${ev.peerCount} peer${if (ev.peerCount == 1) "" else "s"}"
                else null
            is MeshEvent.SettledByPeer     -> "✓ settled by relay — tx ${ev.txHash.take(10)}…"
            else -> null
        } ?: return
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private suspend fun handleIncomingVoucher(v: Voucher, endorsement: Endorsement? = null) {
        // True-bearer cards (recipient = 0x0) settle via the endorsed path:
        // no recipient binding to check, but sig/expiry/amount/dedupe apply.
        val isBearer = v.isTrueBearer
        val result = if (isBearer) verifier.verifyForMesh(v) else verifier.verify(v)
        Log.d(TAG, "BT verify ${v.voucherId.take(10)} bearer=$isBearer -> $result")

        if (isBearer && endorsement == null) {
            store.saveRejected(v, "BEARER_NO_ENDORSEMENT")
            btBridge?.sendDecision(false)
            state.value = state.value.copy(
                status = "bearer voucher missing endorsement — rejected",
                statusKind = StatusKind.Error,
            )
            return
        }

        // Re-tap with a fresh endorsement refreshes a stored row whose earlier
        // settle failed, instead of looping on the stale signature.
        if (isBearer && endorsement != null && result == VerifyResult.ALREADY_SEEN) {
            Log.i(TAG, "refreshing endorsement on already-seen voucher ${v.voucherId.take(10)}")
            store.refreshEndorsement(v.voucherId, endorsement)
            btBridge?.sendDecision(true)
            state.value = state.value.copy(
                status = "refreshed endorsement — retrying settle",
                statusKind = StatusKind.Working,
            )
            Settler.run(this)
            return
        }

        if (result == VerifyResult.VALID) {
            store.saveAccepted(v, endorsement)
            activity.recordReceived(v.voucherId, v.payer, v.amount.toLong())
            WalletMesh.broadcast(v, endorsement)
            btBridge?.sendDecision(true)
            val amt = Money.fmt(v.amount.toLong())
            val from = v.payer.take(6) + "…" + v.payer.takeLast(4)
            state.value = state.value.copy(
                status = "💳 Card payment received: \$$amt from $from",
                statusKind = StatusKind.Success,
            )
            // The merchant is usually watching the reader, not the status line.
            runOnUiThread {
                Toast.makeText(this, "💳 Received \$$amt by card", Toast.LENGTH_LONG).show()
            }
        } else {
            store.saveRejected(v, result.name)
            btBridge?.sendDecision(false)
            state.value = state.value.copy(
                status = "voucher rejected: ${result.name}",
                statusKind = StatusKind.Error,
            )
        }
        Settler.run(this)
    }

    private suspend fun handleIncoming(wireJson: String) {
        Log.d(TAG, "handleIncoming raw=$wireJson")
        val list = try {
            Voucher.listFromWireJson(wireJson)
        } catch (t: Throwable) {
            Log.e(TAG, "bad json", t)
            state.value = state.value.copy(status = "bad voucher data: ${t.message}", statusKind = StatusKind.Error)
            return
        }
        var totalAccepted = 0L
        var alreadyHave = 0
        var rejected = 0
        for (v in list) {
            val result = verifier.verify(v)
            Log.d(TAG, "verify ${v.voucherId.take(10)} -> $result")
            when (result) {
                VerifyResult.VALID -> {
                    store.saveAccepted(v)
                    activity.recordReceived(v.voucherId, v.payer, v.amount.toLong())
                    WalletMesh.broadcast(v)
                    totalAccepted += v.amount.toLong()
                }
                // Same payment already arrived over the mesh — not an error.
                VerifyResult.ALREADY_SEEN -> alreadyHave += 1
                else -> {
                    store.saveRejected(v, result.name)
                    rejected += 1
                }
            }
        }
        val from = list.firstOrNull()?.payer?.let { " from ${it.take(6)}…${it.takeLast(4)}" } ?: ""
        val summary = when {
            rejected > 0 -> "received ${list.size - rejected}/${list.size} (${rejected} rejected: " +
                "${if (list.any { !it.recipient.equals(keyVault.address, true) }) "not addressed to you" else "invalid"})"
            totalAccepted == 0L && alreadyHave > 0 -> "✓ already received this payment$from"
            else -> "✓ received \$${Money.fmt(totalAccepted)}$from"
        }
        state.value = state.value.copy(status = summary,
            statusKind = if (rejected == 0) StatusKind.Success else StatusKind.Error)
        BalanceEngine.invalidate()
    }

    override fun onResume() {
        super.onResume()
        WalletMesh.acquire(this)
        reader.start()
        btBridge?.connect()
        Settler.kick(this)
    }

    override fun onPause() {
        reader.stop()
        btBridge?.close()
        WalletMesh.release()
        super.onPause()
    }

    companion object { private const val TAG = "OfflinePay/Receive" }
}
