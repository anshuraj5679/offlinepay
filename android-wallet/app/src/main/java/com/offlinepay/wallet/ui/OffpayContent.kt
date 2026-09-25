package com.offlinepay.wallet.ui

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/// setContent for every OFFPAY screen. Android 15+ forces edge-to-edge for
/// apps targeting SDK 35+, which put headers under the status bar and
/// buttons under the gesture bar on newer phones. Opt in explicitly on every
/// version and pad by the system-bar/cutout insets so layout is identical
/// on Android 8 through 16.
fun ComponentActivity.setOffpayContent(dark: Boolean = false, content: @Composable () -> Unit) {
    val transparent = android.graphics.Color.TRANSPARENT
    val style = if (dark) SystemBarStyle.dark(transparent)
                else SystemBarStyle.light(transparent, transparent)
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    val background = if (dark) Color(0xFF14171B) else OffpayColors.White
    setContent {
        OffpayTheme {
            Box(Modifier.fillMaxSize().background(background).safeDrawingPadding()) {
                content()
            }
        }
    }
}
