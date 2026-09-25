package com.offlinepay.wallet

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.offlinepay.wallet.ui.PhoneCardScreen
import com.offlinepay.wallet.ui.PhoneCardState
import com.offlinepay.wallet.ui.StatusKind
import com.offlinepay.wallet.ui.setOffpayContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicBoolean

/// Load a MIFARE card with the phone's own NFC — no ESP32, no typing the
/// card UID. The phone reads the UID off the card, signs a true-bearer
/// voucher bound to it (same as the reader path), and writes it in the
/// layout the ESP32 reader parses at spend time.
class PhoneCardWriteActivity : ComponentActivity() {

    private val ui = MutableStateFlow(PhoneCardState())
    private var nfc: NfcAdapter? = null
    /// Exactly one voucher per Load press, however many times the card is
    /// re-detected while it sits on the phone.
    private val claimed = AtomicBoolean(false)
    @Volatile private var armedAmount = 0L

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        nfc = NfcAdapter.getDefaultAdapter(this)
        if (!MifareCardWriter.phoneSupportsMifareClassic(this)) {
            ui.value = ui.value.copy(
                status = "This phone's NFC can't write MIFARE Classic cards — load the card through the ESP32 reader instead",
                statusKind = StatusKind.Error,
            )
        }
        setOffpayContent {
            val st by ui.collectAsState()
            PhoneCardScreen(
                state = st,
                onAmountChange = { ui.value = ui.value.copy(amount = it) },
                onLoad = { onLoad() },
                onCancel = { disarm("cancelled", StatusKind.Idle) },
                onClose = { finish() },
            )
        }
    }

    override fun onPause() {
        // Reader mode only works in the foreground; drop it with the screen.
        if (ui.value.waitingForCard) disarm("", StatusKind.Idle)
        super.onPause()
    }

    private fun onLoad() {
        if (!MifareCardWriter.phoneSupportsMifareClassic(this)) return
        if (nfc?.isEnabled != true) {
            fail("Turn on NFC in the phone's settings first"); return
        }
        val want = Money.parse(ui.value.amount)
        if (want == null || want <= 0L) { fail("Enter a valid amount"); return }

        // Same limits as Pay: the card draws on offline-vault money only.
        val v = BalanceEngine.view.value
        val online = Net.isOnline(this)
        when {
            want <= v.sendableOffline -> arm(want)
            want <= v.sendableOnline && online -> {
                ui.value = ui.value.copy(busy = true, status = "moving funds into your offline vault…",
                    statusKind = StatusKind.Working)
                lifecycleScope.launch {
                    runCatching { AutoLocker.lockAll(this@PhoneCardWriteActivity) }
                        .onFailure { ui.value = ui.value.copy(busy = false); fail("couldn't lock funds: ${Errors.friendly(it)}"); return@launch }
                    ui.value = ui.value.copy(busy = false)
                    if (want <= BalanceEngine.view.value.sendableOffline) arm(want)
                    else fail("lock not visible on chain yet — try again in a few seconds")
                }
            }
            want <= v.sendableOnline ->
                fail("\$${Money.fmt(want - v.sendableOffline)} is in your wallet but not locked for offline use — connect to the internet once, then load")
            want <= v.balance ->
                fail("\$${Money.fmt(v.receiving)} is still settling on chain — available now: \$${Money.fmt(v.sendableOffline)}")
            else -> fail("insufficient balance — have \$${Money.fmt(v.balance)}, need \$${Money.fmt(want)}")
        }
    }

    private fun arm(amount: Long) {
        armedAmount = amount
        claimed.set(false)
        ui.value = ui.value.copy(
            waitingForCard = true,
            status = "Hold the card flat on the back of the phone…",
            statusKind = StatusKind.Armed,
        )
        nfc?.enableReaderMode(
            this, { tag -> onTag(tag) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
    }

    private fun disarm(status: String, kind: StatusKind) {
        runCatching { nfc?.disableReaderMode(this) }
        ui.value = ui.value.copy(waitingForCard = false, busy = false, status = status, statusKind = kind)
    }

    private fun onTag(tag: Tag) {
        if (!claimed.compareAndSet(false, true)) return
        val amount = armedAmount
        val uid = MifareCardWriter.uidHex(tag)
        lifecycleScope.launch {
            ui.value = ui.value.copy(busy = true, status = "card $uid — writing, keep holding…",
                statusKind = StatusKind.Working)
            val result = withContext(Dispatchers.IO) {
                val kv = KeyVault(this@PhoneCardWriteActivity)
                val signed = VoucherSigner(
                    chainId = Config.CHAIN_ID,
                    vaultAddress = Config.VAULT_ADDRESS,
                    keyPair = kv.keyPair,
                    payerAddress = kv.address,
                    nonces = NonceTracker(this@PhoneCardWriteActivity),
                ).signNextBearerForCard(
                    amountUsdc = BigInteger.valueOf(amount),
                    ttlSeconds = Config.DEFAULT_TTL_SECONDS,
                )
                // A voucher that never made it onto the card is simply never
                // spendable — so only a completed write is booked as sent.
                when (val r = MifareCardWriter.write(tag, signed.toCardJson(cardUid = uid))) {
                    is MifareCardWriter.Result.Ok -> {
                        ActivityStore(this@PhoneCardWriteActivity)
                            .recordSent(signed.voucherId, "card:$uid", amount)
                        null
                    }
                    is MifareCardWriter.Result.Failed -> r.reason
                }
            }
            if (result == null) {
                disarm("✓ loaded \$${Money.fmt(amount)} onto card $uid", StatusKind.Success)
                BalanceEngine.invalidate()
            } else {
                Log.w(TAG, "card write failed: $result")
                // Let the user retry with the same press: re-arm for another tap.
                claimed.set(false)
                ui.value = ui.value.copy(busy = false, status = "$result", statusKind = StatusKind.Error)
            }
        }
    }

    private fun fail(msg: String) {
        ui.value = ui.value.copy(status = msg, statusKind = StatusKind.Error)
    }

    companion object { private const val TAG = "OfflinePay/PhoneCard" }
}
