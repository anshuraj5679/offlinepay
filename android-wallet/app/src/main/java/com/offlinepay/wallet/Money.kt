package com.offlinepay.wallet

import java.util.Locale

/// USDC amounts are 6-decimal base units everywhere except the UI edge.
object Money {
    /// "1.5", "1,50", " 2 " → base units. Accepts a comma decimal separator
    /// because many phone keyboards/locales type one. Null if unparsable.
    fun parse(text: String): Long? {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty() || t.count { it == '.' } > 1) return null
        val parts = t.split(".")
        val whole = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
        val fracText = parts.getOrNull(1) ?: ""
        if (fracText.any { !it.isDigit() } || whole < 0) return null
        val frac = fracText.padEnd(6, '0').take(6).toLong()
        return whole * 1_000_000 + frac
    }

    /// Always '.'-separated regardless of device locale.
    fun fmt(baseUnits: Long): String = String.format(Locale.US, "%.2f", baseUnits / 1e6)
}
