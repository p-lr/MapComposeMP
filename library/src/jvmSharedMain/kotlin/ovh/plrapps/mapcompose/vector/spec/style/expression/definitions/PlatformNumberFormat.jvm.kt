package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import java.math.RoundingMode
import java.text.NumberFormat as JavaNumberFormat
import java.util.Currency
import java.util.Locale

/**
 * `java.text.NumberFormat`, which is CLDR-backed on desktop and ICU-backed on Android.
 *
 * Two things it does not do on its own:
 * - **Rounding.** Java's default is `HALF_EVEN`, and ECMA-402's is `halfExpand`, so they disagree
 *   on every tie: `2.5` at zero fraction digits is `2` against `3`. `HALF_UP` is Java's name for
 *   rounding away from zero, which is what `halfExpand` is.
 * - **A currency's digit count.** `DecimalFormat.setCurrency` documents that it leaves the fraction
 *   digits alone, so a JPY amount formatted by an `en-US` instance would keep the locale's 2 rather
 *   than the currency's 0. ECMA-402 takes its defaults from the currency, hence the explicit
 *   assignment before the caller's own options are applied over it.
 */
internal actual fun formatNumberPlatform(
    value: Double,
    locale: String?,
    currency: String?,
    minFractionDigits: Int?,
    maxFractionDigits: Int?,
): String {
    val javaLocale = locale?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault(Locale.Category.FORMAT)
    val format = if (currency != null) {
        JavaNumberFormat.getCurrencyInstance(javaLocale)
    } else {
        JavaNumberFormat.getInstance(javaLocale)
    }
    format.roundingMode = RoundingMode.HALF_UP

    if (currency != null) {
        // An unknown code is upstream's RangeError; keeping the locale's own currency instead is
        // the same choice made for an unrecognized unit.
        runCatching { Currency.getInstance(currency) }.getOrNull()?.let {
            format.currency = it
            val digits = it.defaultFractionDigits.coerceAtLeast(0)
            format.minimumFractionDigits = digits
            format.maximumFractionDigits = digits
        }
    }

    // Maximum first: raising the minimum past it is what carries the maximum along, which is
    // ECMA-402's `max(mxfdDefault, mnfd)`.
    maxFractionDigits?.let { format.maximumFractionDigits = it }
    minFractionDigits?.let { format.minimumFractionDigits = it }

    return format.format(value)
}
