package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import platform.Foundation.NSDecimalNumber
import platform.Foundation.NSLocale
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterCurrencyStyle
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.NSNumberFormatterRoundHalfUp
import platform.Foundation.currentLocale

/**
 * `NSNumberFormatter`, which is ICU underneath and so carries the same CLDR data upstream's
 * `Intl.NumberFormat` reads.
 *
 * The number handed to it is an [NSDecimalNumber] built from [plainDecimalString], **not** the
 * double. ICU formats the exact binary value of a double, so
 * `["number-format", 987654321.234567, {"max-fraction-digits": 20}]` would expand to
 * `987,654,321.23456704616546630859` here where a browser and `java.text` both stop at
 * `987,654,321.234567` -- they format from the shortest representation that round-trips, which is
 * what the decimal string is.
 *
 * `NSNumberFormatterRoundHalfUp` is Foundation's name for rounding a tie away from zero, i.e.
 * ECMA-402's `halfExpand`. Unlike `java.text`, ICU's `setCurrency` does update the fraction digits
 * to the currency's own, so there is nothing to restate here.
 */
internal actual fun formatNumberPlatform(
    value: Double,
    locale: String?,
    currency: String?,
    minFractionDigits: Int?,
    maxFractionDigits: Int?,
): String {
    val decimal = plainDecimalString(value)

    val formatter = NSNumberFormatter().apply {
        numberStyle = if (currency != null) NSNumberFormatterCurrencyStyle else NSNumberFormatterDecimalStyle
        setLocale(locale?.let { NSLocale(localeIdentifier = it) } ?: NSLocale.currentLocale)
        setRoundingMode(NSNumberFormatterRoundHalfUp)
        if (currency != null) setCurrencyCode(currency)
        // Maximum first: raising the minimum past it carries the maximum along, which is
        // ECMA-402's `max(mxfdDefault, mnfd)`.
        maxFractionDigits?.let { setMaximumFractionDigits(it.toULong()) }
        minFractionDigits?.let { setMinimumFractionDigits(it.toULong()) }
    }

    return formatter.stringFromNumber(NSDecimalNumber(string = decimal)) ?: decimal
}
