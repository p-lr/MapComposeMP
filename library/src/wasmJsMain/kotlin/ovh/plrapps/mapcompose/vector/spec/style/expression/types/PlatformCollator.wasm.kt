package ovh.plrapps.mapcompose.vector.spec.style.expression.types

/**
 * `Intl.Collator` itself, so this target is upstream's behaviour by construction -- including the
 * `search` collation tailorings the other two targets cannot reach.
 *
 * An absent locale is passed as the empty string rather than as a null, because `undefined` is what
 * makes ECMA-402 fall back to the host default, and that is precisely what a null argument means
 * here. It is the same sentinel `formatNumberPlatform` uses.
 */
internal actual fun compareLocalized(
    lhs: String,
    rhs: String,
    sensitivity: String,
    locale: String?,
): Int = intlCompare(lhs, rhs, sensitivity, locale ?: "")

internal actual fun resolveLocalePlatform(locale: String?): String = intlResolvedLocale(locale ?: "")

/**
 * `usage: 'search'` is upstream's, and it is what makes German "ü" equal "ue" and Swedish "ä" a
 * letter of its own.
 *
 * The `catch` keeps this target in step with the others: `Intl.Collator`'s constructor throws a
 * `RangeError` for a malformed locale tag, where `java.text.Collator` and `NSLocale` quietly fall
 * back. Upstream lets that throw reach `StyleExpression.evaluate`, which drops the property to its
 * default; comparing under the host default is the more useful failure, and it is the same choice
 * `formatNumberPlatform` makes.
 */
private fun intlCompare(lhs: String, rhs: String, sensitivity: String, locale: String): Int = js(
    """{
        var options = {sensitivity: sensitivity, usage: 'search'};
        var collator;
        try {
            collator = new Intl.Collator(locale === '' ? undefined : locale, options);
        } catch (e) {
            collator = new Intl.Collator(undefined, options);
        }
        return Math.sign(collator.compare(lhs, rhs));
    }"""
)

/**
 * Built **without** `usage: 'search'`, as upstream is: it wants the locale the platform resolved to,
 * not the one a search collation would name.
 */
private fun intlResolvedLocale(locale: String): String = js(
    """{
        try {
            return new Intl.Collator(locale === '' ? undefined : locale).resolvedOptions().locale;
        } catch (e) {
            return new Intl.Collator(undefined).resolvedOptions().locale;
        }
    }"""
)
