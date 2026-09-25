package com.offlinepay.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class PhoneCardState(
    val amount: String = "1.00",
    /// Waiting for the user to hold a card on the phone.
    val waitingForCard: Boolean = false,
    val busy: Boolean = false,
    val status: String = "",
    val statusKind: StatusKind = StatusKind.Idle,
)

@Composable
fun PhoneCardScreen(
    state: PhoneCardState,
    onAmountChange: (String) -> Unit,
    onLoad: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(OffpayColors.White)) {
        TopBar(title = "LOAD CARD", onClose = onClose)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(OffpayColors.Ink).padding(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    MonoLabel("AMOUNT TO LOAD", color = Color.White.copy(alpha = 0.55f))
                    Spacer(Modifier.height(10.dp))
                    AmountField(state.amount, onAmountChange, ink = Color.White, currency = "$")
                    Spacer(Modifier.height(8.dp))
                    MonoLabel("MIFARE CARD · SPEND AT ANY OFFPAY READER", color = Color.White.copy(alpha = 0.45f))
                }
            }
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier.fillMaxWidth().height(200.dp),
                contentAlignment = Alignment.Center,
            ) {
                NfcRipples(active = state.waitingForCard)
                Box(
                    Modifier.size(120.dp).clip(CircleShape)
                        .background(if (state.waitingForCard) OffpayColors.Teal else OffpayColors.OffWhite),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.CreditCard, null,
                        tint = if (state.waitingForCard) OffpayColors.Ink else OffpayColors.InkMuted,
                        modifier = Modifier.size(48.dp))
                }
            }
            if (state.status.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                StatusLine(state.status, state.statusKind)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Enter the amount, tap Load card, then hold the card flat on the back of this " +
                "phone until it says done. The card ID is read automatically — no typing. " +
                "Works offline if the money is already in your offline vault.",
                color = OffpayColors.InkSoft, fontSize = 12.sp,
            )
            Spacer(Modifier.height(20.dp))
        }
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Surface(
                onClick = { if (state.waitingForCard) onCancel() else if (!state.busy) onLoad() },
                shape = RoundedCornerShape(20.dp),
                color = if (state.waitingForCard) OffpayColors.OffWhite else OffpayColors.Ink,
                modifier = Modifier.fillMaxWidth().height(60.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        state.busy -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        state.waitingForCard -> Text("Waiting for card… (tap to cancel)",
                            color = OffpayColors.InkSoft, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        else -> Text("Load card", color = Color.White,
                            fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
