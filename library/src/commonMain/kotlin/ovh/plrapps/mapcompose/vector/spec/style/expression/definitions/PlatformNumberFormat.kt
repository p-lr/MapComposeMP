package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

/**
 * The locale-dependent half of `["number-format", …]`, which only a platform can supply.
 *
 * Upstream calls `Intl.NumberFormat` (`maplibre-style-spec/src/expression/definitions/number_format.ts`).
 * Kotlin Multiplatform has no ICU of its own, but every target this library builds for reaches the
 * same CLDR data through its own API: `Intl.NumberFormat` on wasm, `NSNumberFormatter` on iOS and
 * `java.text.NumberFormat` on Android and desktop. So this is an `expect` rather than a hand-rolled
 * formatter, and grouping separators, decimal separators, currency symbols and a currency's own
 * default fraction-digit count are the platform's to get right.
 *
 * [minFractionDigits] and [maxFractionDigits] are **nullable on purpose**: null means "the default
 * for this style", which is 0 and 3 for a plain number but the currency's own digit count for a
 * currency (JPY has 0, so `123456.789` is `¥123,457`). Passing 0 and 3 down would flatten that, and
 * it is exactly what ECMA-402's `SetNumberFormatDigitOptions` avoids by taking its defaults from the
 * style.
 *
 * An implementation is called once per evaluation and holds no state between calls — see
 * [NumberFormat] for why.
 */
internal expect fun formatNumberPlatform(
    value: Double,
    locale: String?,
    currency: String?,
    minFractionDigits: Int?,
    maxFractionDigits: Int?,
): String

/** ECMA-402 caps both fraction-digit options at 100; a style may write anything. */
private const val MAX_FRACTION_DIGITS = 100

/**
 * Formats one number the way `Intl.NumberFormat` would, delegating everything locale-dependent to
 * [formatNumberPlatform].
 *
 * What is decided here rather than by a platform:
 * - **Non-finite values never reach a formatter.** Upstream produces `NaN`, `∞` and `-∞`;
 *   `NSNumberFormatter`'s default positive-infinity symbol is `+∞`, so this cannot be delegated.
 * - **`min > max` is clamped** rather than raised. `Intl.NumberFormat`'s constructor throws a
 *   `RangeError` for it, which upstream turns into the property's default value; clamping keeps the
 *   number instead, and is the same choice made for an unrecognized unit in [formatWithUnit].
 * - **Units**, which are formatted from a shared table over the plain-number result. See
 *   [NumberFormatUnits].
 */
internal fun formatNumber(
    value: Double,
    locale: String?,
    currency: String?,
    unit: String?,
    minFractionDigits: Int?,
    maxFractionDigits: Int?,
): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "∞" else "-∞"

    val min = minFractionDigits?.coerceIn(0, MAX_FRACTION_DIGITS)
    var max = maxFractionDigits?.coerceIn(0, MAX_FRACTION_DIGITS)
    if (min != null && max != null && min > max) max = min

    // A unit is formatted over the plain-number style, as ECMA-402 does: `currency` and `unit` are
    // mutually exclusive anyway, which `NumberFormat.parse` rejects.
    if (unit != null) {
        val number = formatNumberPlatform(value, locale, null, min, max)
        // CLDR's `one` category in English is "the formatted number is 1 with no fraction digits
        // shown", which is a property of the output and not of the value: 1.4 at zero fraction
        // digits is "1 day", and 1.0 at one is "1.0 days". Reading it back off the string is what
        // gets both right without a plural-rules engine.
        return formatWithUnit(number, unit, isSingular = number.trimStart('-') == "1")
    }

    return formatNumberPlatform(value, locale, currency, min, max)
}

/**
 * The shortest decimal string that round-trips to [value], never in exponent form.
 *
 * `Double.toString` switches to exponent notation outside `1e-3..1e7` on every target, and the one
 * consumer of this — the iOS actual, which formats an `NSDecimalNumber` so ICU does not expand the
 * exact binary value — cannot rely on `NSDecimalNumber(string:)` parsing an exponent. Called with a
 * finite value only; [formatNumber] returns before it for the rest.
 */
internal fun plainDecimalString(value: Double): String {
    val s = value.toString()
    val expIndex = s.indexOfFirst { it == 'e' || it == 'E' }
    if (expIndex < 0) return trimTrailingZeros(s)

    val exponent = s.substring(expIndex + 1).toIntOrNull() ?: return s
    var mantissa = s.substring(0, expIndex)
    val negative = mantissa.startsWith('-')
    if (negative) mantissa = mantissa.substring(1)

    val dot = mantissa.indexOf('.')
    val digits = if (dot < 0) mantissa else mantissa.substring(0, dot) + mantissa.substring(dot + 1)
    // Where the point sits once the exponent is applied, counted from the left of `digits`.
    val pointAt = (if (dot < 0) mantissa.length else dot) + exponent

    val body = when {
        pointAt <= 0 -> "0." + "0".repeat(-pointAt) + digits
        pointAt >= digits.length -> digits + "0".repeat(pointAt - digits.length)
        else -> digits.substring(0, pointAt) + "." + digits.substring(pointAt)
    }
    return if (negative) "-${trimTrailingZeros(body)}" else trimTrailingZeros(body)
}

/**
 * `Double.toString` always writes at least one fraction digit, and `1.0E-7` expands to
 * `0.00000010` -- a trailing zero that is not a digit of the value, where the point of
 * [plainDecimalString] is to state exactly the precision the double has.
 */
private fun trimTrailingZeros(s: String): String =
    if ('.' in s) s.trimEnd('0').trimEnd('.') else s
