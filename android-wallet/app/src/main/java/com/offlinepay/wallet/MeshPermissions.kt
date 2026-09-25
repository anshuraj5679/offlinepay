package com.offlinepay.wallet

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/// Everything Nearby Connections needs on THIS phone to find peers.
///
/// Asking for a permission the OS doesn't know (NEARBY_WIFI_DEVICES below
/// Android 13) is auto-denied; re-asking on every onResume then pauses and
/// resumes the activity in a tight loop, restarting mesh + balance polls
/// each time. So: only applicable, only missing, and at most once per
/// process. Android 12+ also ignores a FINE location request unless COARSE
/// is asked for in the same dialog.
object MeshPermissions {
    @Volatile private var askedThisProcess = false

    enum class Blocker(val message: String) {
        PERMISSION("Allow Nearby devices & Location so other phones can find you"),
        LOCATION_OFF("Turn on Location — Android needs it to find nearby phones"),
        BLUETOOTH_OFF("Turn on Bluetooth — the offline mesh runs over it"),
    }

    fun required(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    fun missing(ctx: Context): List<String> = required().filter {
        ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
    }

    /// Missing permissions to request now, or empty if we already asked.
    fun toRequest(ctx: Context): List<String> {
        if (askedThisProcess) return emptyList()
        val m = missing(ctx)
        if (m.isNotEmpty()) askedThisProcess = true
        return m
    }

    /// First thing stopping the mesh on this phone, or null if it can run.
    fun blocker(ctx: Context): Blocker? {
        if (missing(ctx).isNotEmpty()) return Blocker.PERMISSION
        val bt = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (bt != null && !bt.isEnabled) return Blocker.BLUETOOTH_OFF
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm != null && !LocationManagerCompat.isLocationEnabled(lm)) return Blocker.LOCATION_OFF
        return null
    }

    /// Settings screen that clears the given blocker.
    fun fixIntent(ctx: Context, b: Blocker): Intent = when (b) {
        Blocker.LOCATION_OFF -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        Blocker.BLUETOOTH_OFF -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        Blocker.PERMISSION -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", ctx.packageName, null))
    }
}
