package com.offlinepay.wallet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.offlinepay.wallet.ui.SendScreen
import com.offlinepay.wallet.ui.StatusKind
import com.offlinepay.wallet.ui.setOffpayContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigInteger

/// Pay by NFC tap or by scanning the receiver's QR. Both produce the same
/// offline, recipient-bound voucher. Limits come from BalanceEngine, the
/// same source the dashboard shows.
class SendActivity : ComponentActivity() {

    private val amount = MutableStateFlow("0.50")
    private val armed  = MutableStateFlow(false)
    private val status = MutableStateFlow("")
    private val statusKind = MutableStateFlow(StatusKind.Idle)
    private val voucherQr = MutableStateFlow<Bitmap?>(null)
    @Volatile private var busy = false
    @Volatile private var qrAmount = 0L

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val raw = res.data?.getStringExtra("qr")
        if (res.resultCode == RESULT_OK && raw != null) payScannedReceiver(raw)
        else { busy = false; status.value = "scan cancelled"; statusKind.value = StatusKind.Idle }
    }
    private val camPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) scanLauncher.launch(Intent(this, QrScanActivity::class.java))
        else { busy = false; status.value = "camera permission is needed to scan"; statusKind.value = StatusKind.Error }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val bondStore = EspBondStore(this)
        val phoneCanWriteCards = MifareCardWriter.phoneSupportsMifareClassic(this)
        setOffpayContent {
            val a by amount.collectAsState()
            val ar by armed.collectAsState()
            val st by status.collectAsState()
            val sk by statusKind.collectAsState()
            val qr by voucherQr.collectAsState()
            val bond by bondStore.stateFlow.collectAsState()
            SendScreen(
                amount = a,
                onAmountChange = { amount.value = it },
                armed = ar,
                status = st,
                statusKind = sk,
                onArm = { onArm() },
                onClose = { finish() },
                // Load a MIFARE card: straight from this phone's NFC when the
                // chip supports MIFARE Classic (reads the UID itself); otherwise
                // through a paired ESP32 reader.
                onWriteCard = when {
                    phoneCanWriteCards -> {
                        { startActivity(Intent(this, PhoneCardWriteActivity::class.java)) }
                    }
                    bond.isPaired -> {
                        { startActivity(Intent(this, CardWriteActivity::class.java)) }
                    }
                    else -> null
                },
                onPayByQr = { onPayByQr() },
                voucherQr = qr,
                onDismissQr = { voucherQr.value = null },
            )
        }
        // Idle status line = what can be sent right now.
        lifecycleScope.launch {
            BalanceEngine.view.collect { v ->
                if (!armed.value && !busy && statusKind.value != StatusKind.Success) {
                    status.value = availableText(v)
                    statusKind.value = if (v.sendableOnline > 0 || v.balance > 0) StatusKind.Idle else StatusKind.Error
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        BalanceSync.refreshAsync(this)
    }

    override fun onDestroy() {
        // Don't leave a payment armed after the user backs out — a later
        // accidental tap would otherwise sign it.
        if (isFinishing && armed.value) PendingPayment.cancel(this)
        super.onDestroy()
    }

    private fun availableText(v: BalanceView): String = when {
        v.balance == 0L -> "no balance — top up before sending"
        Net.isOnline(this) -> "available to send: \$${Money.fmt(v.sendableOnline)}"
        else -> "available offline: \$${Money.fmt(v.sendableOffline)}"
    }

    private fun onArm() {
        if (busy || armed.value) return
        val want = parseAmount() ?: return
        prepare(want) { actuallyArm(want) }
    }

    private fun onPayByQr() {
        if (busy || armed.value) return
        val want = parseAmount() ?: return
        prepare(want) {
            busy = true
            qrAmount = want
            voucherQr.value = null
            status.value = "scan the receiver's QR (Receive screen)"
            statusKind.value = StatusKind.Working
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) scanLauncher.launch(Intent(this, QrScanActivity::class.java))
            else camPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun parseAmount(): Long? {
        val want = Money.parse(amount.value)
        if (want == null || want <= 0L) {
            status.value = "enter a valid amount"; statusKind.value = StatusKind.Error
            return null
        }
        return want
    }

    /// Make sure `want` is signable offline, locking wallet USDC into the
    /// vault first if needed (online only), then run [then].
    private fun prepare(want: Long, then: () -> Unit) {
        val v = BalanceEngine.view.value
        val online = Net.isOnline(this)
        when {
            want <= v.sendableOffline -> then()
            want <= v.sendableOnline && online -> lockThen(want, want - v.sendableOffline, then)
            want <= v.sendableOnline -> {
                status.value = "\$${Money.fmt(want - v.sendableOffline)} is in your wallet but not yet " +
                    "locked for offline use — connect to the internet once, then send"
                statusKind.value = StatusKind.Error
            }
            want <= v.balance -> {
                status.value = "\$${Money.fmt(v.receiving)} is still settling on chain — you can spend it " +
                    "once it lands (available now: \$${Money.fmt(if (online) v.sendableOnline else v.sendableOffline)})"
                statusKind.value = StatusKind.Error
            }
            else -> {
                status.value = "insufficient balance — have \$${Money.fmt(v.balance)}, need \$${Money.fmt(want)}"
                statusKind.value = StatusKind.Error
            }
        }
    }

    /// Wallet USDC has to be inside the vault before an offline voucher can
    /// draw on it. Lock it (needs internet once), then continue.
    private fun lockThen(want: Long, deficit: Long, then: () -> Unit) {
        busy = true
        status.value = "moving \$${Money.fmt(deficit)} into your offline vault…"
        statusKind.value = StatusKind.Working
        lifecycleScope.launch {
            runCatching { AutoLocker.lockAll(this@SendActivity) }
                .onSuccess {
                    busy = false
                    if (want <= BalanceEngine.view.value.sendableOffline) then()
                    else {
                        status.value = "lock not visible on chain yet — try again in a few seconds"
                        statusKind.value = StatusKind.Error
                    }
                }
                .onFailure {
                    busy = false
                    Log.w(TAG, "lock before send failed", it)
                    status.value = "couldn't lock funds: ${Errors.friendly(it)}"
                    statusKind.value = StatusKind.Error
                }
        }
    }

    private fun actuallyArm(baseUnits: Long) {
        PendingPayment.arm(this, BigInteger.valueOf(baseUnits))
        armed.value = true
        status.value = "armed · hold near receiver"
        statusKind.value = StatusKind.Armed

        lifecycleScope.launch {
            while (PendingPayment.isArmed(this@SendActivity)) delay(200)
            armed.value = false
            val err = PendingPayment.pollError(this@SendActivity)
            val out = PendingPayment.pollOutgoing(this@SendActivity)
            when {
                err != null -> { status.value = err; statusKind.value = StatusKind.Error }
                out != null -> {
                    status.value = "✓ paid \$${Money.fmt(out.amount.toLong())} to ${short(out.recipient)}"
                    statusKind.value = StatusKind.Success
                }
                else -> { status.value = "tap completed"; statusKind.value = StatusKind.Success }
            }
            BalanceEngine.invalidate()
        }
    }

    /// Receiver's QR holds their wallet address. Sign a voucher bound to it
    /// (fully offline), then deliver it three ways: mesh broadcast (reaches
    /// the receiver and any relay), our own store (so WE settle it too if
    /// we're first online), and an on-screen QR the receiver can scan.
    private fun payScannedReceiver(raw: String) {
        val want = qrAmount
        val me = KeyVault(this).address.lowercase()
        val receiver = ADDRESS_RE.find(raw)?.value?.lowercase()
        when {
            receiver == null -> fail("that QR isn't an OFFPAY receive code")
            receiver == me -> fail("that's your own QR — scan the receiver's phone")
            want > BalanceEngine.view.value.sendableOffline -> fail("balance changed — check the amount and try again")
            else -> lifecycleScope.launch {
                runCatching { withContext(Dispatchers.IO) { signAndDeliver(receiver, want) } }
                    .onSuccess { bmp ->
                        voucherQr.value = bmp
                        status.value = "✓ paid \$${Money.fmt(want)} to ${short(receiver)} — sent over mesh; " +
                            "they can also scan the QR below"
                        statusKind.value = StatusKind.Success
                    }
                    .onFailure {
                        Log.e(TAG, "QR payment failed", it)
                        fail("couldn't sign payment: ${Errors.friendly(it)}")
                    }
                busy = false
            }
        }
    }

    private suspend fun signAndDeliver(receiver: String, want: Long): Bitmap {
        val kv = KeyVault(this)
        val signer = VoucherSigner(
            chainId = Config.CHAIN_ID,
            vaultAddress = Config.VAULT_ADDRESS,
            keyPair = kv.keyPair,
            payerAddress = kv.address,
            nonces = NonceTracker(this),
        )
        val signed = signer.signNext(
            merchant = null,
            recipient = receiver,
            amountUsdc = BigInteger.valueOf(want),
            ttlSeconds = Config.DEFAULT_TTL_SECONDS,
        )
        val voucher = Voucher(
            voucherId = signed.voucherId, payer = signed.payer,
            merchant = signed.merchant, recipient = signed.recipient,
            amount = signed.amount, expiry = signed.expiry,
            nonce = signed.nonce, signature = signed.signature,
        )
        ActivityStore(this).recordSent(signed.voucherId, receiver, want)
        VoucherStore(this).saveReplica(voucher)
        WalletMesh.broadcast(voucher)
        Settler.kick(this)
        return Qr.render("[" + signed.toCardJson() + "]", size = 720)
    }

    private fun fail(msg: String) {
        busy = false
        status.value = msg
        statusKind.value = StatusKind.Error
    }

    private fun short(a: String) = a.take(6) + "…" + a.takeLast(4)

    companion object {
        private const val TAG = "OfflinePay/Send"
        private val ADDRESS_RE = Regex("0x[0-9a-fA-F]{40}")
    }
}
