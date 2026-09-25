package com.offlinepay.wallet

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object Net {
    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val cap = cm.getNetworkCapabilities(net) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
