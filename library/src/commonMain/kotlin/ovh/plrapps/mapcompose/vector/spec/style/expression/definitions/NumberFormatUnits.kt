package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

/**
 * The `unit` half of `["number-format", …]`.
 *
 * Everything else about the expression is delegated to the platform's own ICU
 * ([formatNumberPlatform]), but CLDR *unit* names are the one piece of locale data no JVM API
 * exposes: `NSMeasurementFormatter` and `android.icu.text.MeasureFormat` have them, desktop JVM
 * does not, and three targets agreeing while the fourth appends a raw name is worse than four
 * agreeing. So this is CLDR's **English short** data, held in common and used by every target: the
 * number is localized, the unit symbol is not.
 *
 * Most of these are SI or otherwise language-neutral symbols (`m`, `kg`, `°C`, `kB`), which is what
 * makes the compromise tolerable; the word-like ones (`day`, `yr`) are where a non-English locale
 * shows it.
 *
 * The set is ECMA-402's sanctioned single-unit list. Anything outside it — including a `-per-`
 * pair whose halves are not both known — falls back to `"<number> <name>"`, where upstream's
 * `Intl.NumberFormat` constructor throws a `RangeError` and loses the whole property to its
 * default. Keeping the number is the more useful failure.
 */

/** A CLDR pattern pair. `{0}` is the formatted number; English needs only `one` and `other`. */
private class UnitPattern(val one: String, val other: String = one)

private fun p(one: String, other: String = one) = UnitPattern(one, other)

/**
 * CLDR `en` `unitPattern-count-*`, `short` width.
 *
 * A leading space is part of the pattern, which is why the temperatures and `percent` are written
 * without one — `["number-format", 1234.567, {"unit": "celsius"}]` is `1,234.567°C`, not
 * `1,234.567 °C`.
 */
private val UNIT_PATTERNS: Map<String, UnitPattern> = mapOf(
    "acre" to p("{0} ac"),
    "bit" to p("{0} bit"),
    "byte" to p("{0} byte"),
    "celsius" to p("{0}°C"),
    "centimeter" to p("{0} cm"),
    "day" to p("{0} day", "{0} days"),
    "degree" to p("{0} deg"),
    "fahrenheit" to p("{0}°F"),
    "fluid-ounce" to p("{0} fl oz"),
    "foot" to p("{0} ft"),
    "gallon" to p("{0} gal"),
    "gigabit" to p("{0} Gb"),
    "gigabyte" to p("{0} GB"),
    "gram" to p("{0} g"),
    "hectare" to p("{0} ha"),
    "hour" to p("{0} hr"),
    "inch" to p("{0} in"),
    "kilobit" to p("{0} kb"),
    "kilobyte" to p("{0} kB"),
    "kilogram" to p("{0} kg"),
    "kilometer" to p("{0} km"),
    "liter" to p("{0} L"),
    "megabit" to p("{0} Mb"),
    "megabyte" to p("{0} MB"),
    "meter" to p("{0} m"),
    "microsecond" to p("{0} μs"),
    "mile" to p("{0} mi"),
    "mile-scandinavian" to p("{0} smi"),
    "milliliter" to p("{0} mL"),
    "millimeter" to p("{0} mm"),
    "millisecond" to p("{0} ms"),
    "minute" to p("{0} min"),
    "month" to p("{0} mth", "{0} mths"),
    "nanosecond" to p("{0} ns"),
    "ounce" to p("{0} oz"),
    "percent" to p("{0}%"),
    "petabyte" to p("{0} PB"),
    "pound" to p("{0} lb"),
    "second" to p("{0} sec"),
    "stone" to p("{0} st"),
    "terabit" to p("{0} Tb"),
    "terabyte" to p("{0} TB"),
    "week" to p("{0} wk", "{0} wks"),
    "yard" to p("{0} yd"),
    "year" to p("{0} yr", "{0} yrs"),
)

/**
 * CLDR `perUnitPattern`, `short` width, for the denominators that abbreviate differently there —
 * `kilometer-per-hour` is `km/h`, not `km/hr`. A denominator with no entry contributes the symbol
 * from its own pattern.
 */
private val PER_UNIT_SYMBOLS: Map<String, String> = mapOf(
    "second" to "s",
    "minute" to "min",
    "hour" to "h",
    "day" to "d",
    "week" to "w",
    "month" to "m",
    "year" to "y",
)

/** The unit's own symbol, i.e. its plural pattern with the number and the separating space cut. */
private fun symbolOf(unit: String): String? =
    UNIT_PATTERNS[unit]?.other?.removePrefix("{0}")?.trimStart()

/**
 * Applies [unit] to an already-formatted [number].
 *
 * [isSingular] is CLDR's `one` category, which in English is "the value is exactly 1 and no
 * fraction digits are shown" — `1 day`, but `1.0 days`.
 */
internal fun formatWithUnit(number: String, unit: String, isSingular: Boolean): String {
    UNIT_PATTERNS[unit]?.let { pattern ->
        return (if (isSingular) pattern.one else pattern.other).replace("{0}", number)
    }

    val separator = unit.indexOf("-per-")
    if (separator > 0) {
        val numerator = unit.substring(0, separator)
        val denominator = unit.substring(separator + "-per-".length)
        val numeratorSymbol = symbolOf(numerator)
        val denominatorSymbol = PER_UNIT_SYMBOLS[denominator] ?: symbolOf(denominator)
        if (numeratorSymbol != null && denominatorSymbol != null) {
            return "$number $numeratorSymbol/$denominatorSymbol"
        }
    }

    return "$number $unit"
}
