package com.offlinepay.wallet

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/// Process-scoped owner of the mesh stack, the balance engine and the one
/// sync/settle loop. Screens only display state and trigger actions; they
/// no longer run their own pollers (several copies used to race each other).
///
/// The mesh refcount acquired here never drops to 0, so screen transitions
/// (Home→Send→Receive) don't tear down Nearby links.
class OffpayApp : Application() {

    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, t -> Log.w(TAG, "app task failed", t) }
    )

    override fun onCreate() {
        super.onCreate()
        Wallet.init(this)
        WalletMesh.acquire(this)
        BalanceEngine.start(this)

        appScope.launch {
            WalletMesh.events.collect { ev ->
                when (ev) {
                    // A relay just got someone else's voucher: settle it (after
                    // the recipient's grace window) if we're online.
                    is MeshEvent.ReplicaStored -> Settler.kick(this@OffpayApp)
                    // Our own payment arrived over the mesh: settle fee-free if online.
                    is MeshEvent.PaymentReceived -> Settler.kick(this@OffpayApp)
                    is MeshEvent.SettledByPeer -> {
                        FeeLedger.reconcile()
                        BalanceEngine.invalidate()
                        // Re-evaluate what's still pending so the status line clears.
                        Settler.kick(this@OffpayApp)
                    }
                    else -> Unit
                }
            }
        }

        appScope.launch {
            while (true) {
                if (Net.isOnline(this@OffpayApp)) {
                    BalanceSync.refresh(this@OffpayApp)
                    Settler.run(this@OffpayApp)
                }
                delay(SYNC_INTERVAL_MS)
            }
        }

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "network available → sync + settle queued vouchers")
                appScope.launch {
                    BalanceSync.refresh(this@OffpayApp)
                    Settler.run(this@OffpayApp)
                }
            }
        })
    }

    companion object {
        private const val TAG = "OfflinePay/App"
        private const val SYNC_INTERVAL_MS = 20_000L
    }
}
