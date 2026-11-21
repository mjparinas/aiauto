package dev.aiauto.core

/**
 * The Claude API reports and accepts money in minor units: "2500" is $25.00 and "50" is fifty cents.
 * The app works in dollars, so every amount crosses this boundary.
 */
object Money {
    /** "2500" → 25.0. Returns null for anything that isn't a whole number of cents. */
    fun fromCents(amount: String): Double? = amount.trim().toLongOrNull()?.let { it / 100.0 }

    /** 25.0 → "2500". Rounds down so a budget never exceeds what's left, but never below one cent. */
    fun toCents(usd: Double): String = maxOf(1L, kotlin.math.floor(usd * 100 + 1e-6).toLong()).toString()
}
