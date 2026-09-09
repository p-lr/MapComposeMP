package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

/**
 * `Intl.NumberFormat` itself, so this target is upstream's behaviour by construction.
 *
 * The absent options are passed as sentinels rather than as nulls -- an empty locale or currency,
 * a negative digit count -- because leaving a key off the options object is what makes ECMA-402
 * fall back to the style's own default, and that is precisely what a null argument means here.
 */
internal actual fun formatNumberPlatform(
    value: Double,
    locale: String?,
    currency: String?,
    minFractionDigits: Int?,
    maxFractionDigits: Int?,
): String = intlFormat(
    value = value,
    locale = locale ?: "",
    currency = currency ?: "",
    min = minFractionDigits ?: -1,
    max = maxFractionDigits ?: -1,
)

/**
 * The `catch` is what keeps this target in step with the others: `Intl.NumberFormat`'s constructor
 * throws a `RangeError` for an unknown currency code or a malformed locale tag, where
 * `java.text.NumberFormat` and `NSNumberFormatter` quietly fall back. Upstream lets that throw reach
 * `StyleExpression.evaluate`, which drops the property to its default; keeping the number is the
 * more useful failure, and it is the same choice made for an unrecognized unit.
 */
private fun intlFormat(value: Double, locale: String, currency: String, min: Int, max: Int): String = js(
    """{
        var options = {};
        if (currency !== '') { options.style = 'currency'; options.currency = currency; }
        if (min >= 0) { options.minimumFractionDigits = min; }
        if (max >= 0) { options.maximumFractionDigits = max; }
        try {
            return new Intl.NumberFormat(locale === '' ? undefined : locale, options).format(value);
        } catch (e) {
            return new Intl.NumberFormat(undefined).format(value);
        }
    }"""
)
