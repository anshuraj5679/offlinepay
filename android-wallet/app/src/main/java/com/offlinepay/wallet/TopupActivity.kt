package com.offlinepay.wallet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.offlinepay.wallet.ui.StatusKind
import com.offlinepay.wallet.ui.TopupScreen
import com.offlinepay.wallet.ui.setOffpayContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class TopupActivity : ComponentActivity() {

    private val amount = MutableStateFlow("5.00")
    private val busy   = MutableStateFlow(false)
    private val status = MutableStateFlow("")
    private val statusKind = MutableStateFlow(StatusKind.Idle)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val keyVault = KeyVault(this)
        val backend  = BackendClient(Config.BACKEND_BASE)
        val activity = ActivityStore(this)

        setOffpayContent {
            val a by amount.collectAsState()
            val b by busy.collectAsState()
            val st by status.collectAsState()
            val sk by statusKind.collectAsState()
            TopupScreen(
                amount = a,
                onAmountChange = { amount.value = it },
                busy = b,
                status = st,
                statusKind = sk,
                onClose = { finish() },
                onTopup = {
                    if (b) return@TopupScreen
                    val baseUnits = Money.parse(amount.value)
                    if (baseUnits == null || baseUnits <= 0L) {
                        status.value = "enter a valid amount"; statusKind.value = StatusKind.Error
                        return@TopupScreen
                    }
                    if (!Net.isOnline(this)) {
                        status.value = "top-up needs internet"; statusKind.value = StatusKind.Error
                        return@TopupScreen
                    }
                    busy.value = true
                    statusKind.value = StatusKind.Working
                    lifecycleScope.launch {
                        runCatching {
                            status.value = "(1/2) funding gas + minting USDC…"
                            val initResp = backend.init(keyVault.address, baseUnits)
                            if (!initResp.ok) error(initResp.error ?: "funding failed")
                            status.value = "(2/2) locking into your offline vault…"
                            // The mint already credited the wallet, so the ledger
                            // row is recorded whether or not the lock lands now.
                            // Locks everything in the wallet (this top-up plus any
                            // earlier received funds). Null = the background
                            // auto-locker already did it.
                            val lock = runCatching { AutoLocker.lockAll(this@TopupActivity) }
                            activity.recordTopup(baseUnits, lock.getOrNull())
                            status.value = if (lock.isSuccess)
                                "✓ added \$${Money.fmt(baseUnits)} — ready to spend offline"
                            else
                                "✓ added \$${Money.fmt(baseUnits)} — offline lock will retry automatically"
                            statusKind.value = StatusKind.Success
                        }.onFailure {
                            status.value = Errors.friendly(it)
                            statusKind.value = StatusKind.Error
                            BalanceSync.refreshAsync(this@TopupActivity)
                        }
                        busy.value = false
                    }
                },
            )
        }
    }
}
