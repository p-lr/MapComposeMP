package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `["number-format", …]` against `Intl.NumberFormat`, on every target.
 *
 * The MapLibre conformance suite covers the expression itself (the `number-format` fixtures in
 * `ExpressionConformanceTest`), but it runs on the skia targets only and skips the one fixture that
 * passes no locale. This drives [formatNumber] directly instead -- plain `kotlin.test`, no graphics
 * backend -- so androidHostTest is covered too, and every case names its locale, since without one
 * the output follows the host's.
 *
 * Expected values are what a browser console prints for the same options.
 */
class NumberFormatTest {

    private fun format(
        value: Double,
        locale: String? = "en-US",
        currency: String? = null,
        unit: String? = null,
        min: Int? = null,
        max: Int? = null,
    ) = formatNumber(value, locale, currency, unit, min, max)

    // region fraction digits

    /**
     * The formatter used to split at `floor(magnitude)` and round only the fraction, so zero
     * fraction digits truncated: 1.9 formatted as "1".
     */
    @Test
    fun `zero fraction digits rounds rather than truncates`() {
        assertEquals("2", format(1.9, max = 0))
        assertEquals("1", format(1.4, max = 0))
        assertEquals("-2", format(-1.9, max = 0))
    }

    /** ECMA-402 rounds a tie away from zero (`halfExpand`); Java's default is HALF_EVEN, which does not. */
    @Test
    fun `a tie rounds away from zero`() {
        assertEquals("3", format(2.5, max = 0))
        assertEquals("2", format(1.5, max = 0))
        assertEquals("-3", format(-2.5, max = 0))
    }

    /** A value that rounds to zero keeps its sign, as every ICU implementation does. */
    @Test
    fun `negative zero keeps its sign`() {
        assertEquals("-0", format(-0.4, max = 0))
    }

    @Test
    fun `minimum fraction digits pad with zeros`() {
        assertEquals("1.500", format(1.5, min = 3))
        assertEquals("2.00", format(2.0, min = 2))
    }

    /** The spec's defaults are 0 and 3 for a plain number, so the fourth decimal is dropped. */
    @Test
    fun `three fraction digits by default`() {
        assertEquals("1.235", format(1.23456))
    }

    /**
     * A double is formatted from the shortest representation that round-trips, not from its exact
     * binary value -- ICU would expand this one to 987,654,321.23456704616546630859.
     */
    @Test
    fun `a large maximum does not expand a double past its own precision`() {
        assertEquals("987,654,321.234567000000000", format(987654321.234567, min = 15, max = 20))
    }

    /** `Intl.NumberFormat` throws a RangeError for this pair; the port clamps instead. */
    @Test
    fun `a minimum above the maximum is clamped rather than thrown`() {
        assertEquals("1.500", format(1.5, min = 3, max = 1))
    }

    // endregion

    // region locale

    @Test
    fun `grouping and decimal separators follow the locale`() {
        assertEquals("123,456.789", format(123456.789))
        assertEquals("1,234.5", format(1234.5, max = 1))
        assertEquals("1.234,5", format(1234.5, locale = "de-DE", max = 1))
        // French groups with a narrow no-break space, which CLDR has moved between U+202F and
        // U+00A0 over the years; which of the two a host uses is not what this is asserting.
        assertEquals("1 234,5", format(1234.5, locale = "fr-FR", max = 1).replace('\u202f', ' ').replace('\u00a0', ' '))
    }

    // endregion

    // region currency

    /** A currency's own digit count is the default, which is why JPY shows none. */
    @Test
    fun `a currency brings its symbol and its digit count`() {
        assertEquals("€123,456.79", format(123456.789, currency = "EUR"))
        assertEquals("¥123,457", format(123456.789, currency = "JPY"))
    }

    @Test
    fun `explicit digits override the currency's own`() {
        assertEquals("¥123,456.8", format(123456.789, currency = "JPY", max = 1))
    }

    // endregion

    // region units

    @Test
    fun `a unit is appended in CLDR's English short form`() {
        assertEquals("1,234.567 m", format(1234.567, unit = "meter"))
        assertEquals("1,234.567 kB", format(1234.567, unit = "kilobyte"))
    }

    /** Not every unit takes a space: the temperatures and percent are written against the number. */
    @Test
    fun `a temperature carries no space`() {
        assertEquals("1,234.567°C", format(1234.567, unit = "celsius"))
        assertEquals("1,234.567°F", format(1234.567, unit = "fahrenheit"))
        assertEquals("1,234.567%", format(1234.567, unit = "percent"))
    }

    /** English pluralises on the formatted number, not on the value: 1.4 rounded to "1" is singular. */
    @Test
    fun `a word-like unit is pluralised`() {
        assertEquals("1 day", format(1.0, unit = "day"))
        assertEquals("2 days", format(2.0, unit = "day"))
        assertEquals("1.0 days", format(1.0, unit = "day", min = 1))
        assertEquals("1 day", format(1.4, unit = "day", max = 0))
        assertEquals("-1 day", format(-1.0, unit = "day"))
    }

    @Test
    fun `a per-unit composes both halves`() {
        assertEquals("90 km/h", format(90.0, unit = "kilometer-per-hour"))
        assertEquals("9.81 m/s", format(9.81, unit = "meter-per-second", max = 2))
    }

    /** Upstream loses the whole property to a RangeError here; the port keeps the number. */
    @Test
    fun `an unknown unit falls back to its own name`() {
        assertEquals("1,234.567 parsec", format(1234.567, unit = "parsec"))
    }

    // endregion

    // region non-finite

    @Test
    fun `non-finite values are spelled as upstream spells them`() {
        assertEquals("NaN", format(Double.NaN))
        assertEquals("∞", format(Double.POSITIVE_INFINITY))
        assertEquals("-∞", format(Double.NEGATIVE_INFINITY))
    }

    // endregion

    // region plainDecimalString

    /**
     * `Double.toString` leaves exponent notation outside 1e-3..1e7 on every target, and
     * `NSDecimalNumber(string:)` is what has to read it back on iOS.
     */
    @Test
    fun `plainDecimalString expands exponent notation`() {
        assertEquals("987654321.234567", plainDecimalString(987654321.234567))
        assertEquals("1.5", plainDecimalString(1.5))
        assertEquals("0.0000001", plainDecimalString(1e-7))
        assertEquals("-0.0000001", plainDecimalString(-1e-7))
        assertEquals("100000000000000000000", plainDecimalString(1e20))
        assertEquals("0", plainDecimalString(0.0))
    }

    // endregion
}
