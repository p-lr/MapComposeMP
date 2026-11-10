package ovh.plrapps.mapcompose.vector.spec.style.expression.types

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCaseInsensitiveSearch
import platform.Foundation.NSDiacriticInsensitiveSearch
import platform.Foundation.NSLocale
import platform.Foundation.NSMakeRange
import platform.Foundation.NSString
import platform.Foundation.NSStringCompareOptions
import platform.Foundation.availableLocaleIdentifiers
import platform.Foundation.canonicalLanguageIdentifierFromString
import platform.Foundation.compare
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier

/**
 * `NSString.compare(_:options:range:locale:)`, which is ICU underneath and so carries the same CLDR
 * collation data upstream's `Intl.Collator` reads.
 *
 * **A locale is always passed**, the current one when the style named none: it is the `locale:`
 * argument, not the options, that selects ICU collation over a literal code-unit compare, so
 * dropping it would lose the tailoring this whole file exists for.
 *
 * Foundation has no strength setting, so the four ICU sensitivities map onto its two folding
 * options: `base` ignores both, `accent` keeps diacritics, `case` keeps case, `variant` keeps both.
 * That is not quite ICU's strength ladder -- `NSDiacriticInsensitiveSearch` folds a mark away rather
 * than demoting it to a secondary difference, so a locale where an accented letter is a *letter*
 * reads it as its base at `base` sensitivity: Swedish "ä" == "a" here, where `Intl` says "ä" > "z".
 * The ordering against "z" is right either way; see [Collator] for the full list of what differs.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun compareLocalized(
    lhs: String,
    rhs: String,
    sensitivity: String,
    locale: String?,
): Int {
    val options: NSStringCompareOptions = when (sensitivity) {
        "accent" -> NSCaseInsensitiveSearch
        "case" -> NSDiacriticInsensitiveSearch
        "variant" -> 0uL
        else -> NSCaseInsensitiveSearch or NSDiacriticInsensitiveSearch
    }
    val string = lhs as NSString
    val result = string.compare(
        string = rhs,
        options = options,
        range = NSMakeRange(0uL, string.length),
        locale = locale?.let { NSLocale(localeIdentifier = it) } ?: NSLocale.currentLocale,
    )
    return result.toInt()
}

/**
 * ECMA-402's lookup matcher: canonicalize, then drop subtags from the right until the tag names a
 * locale Foundation has data for, and answer with the current locale when none does.
 *
 * `canonicalLanguageIdentifierFromString` is the BCP-47 spelling (hyphens), where
 * `availableLocaleIdentifiers` and `NSLocale.localeIdentifier` are the POSIX one (underscores),
 * hence the normalisation on both sides.
 */
internal actual fun resolveLocalePlatform(locale: String?): String {
    val fallback = bcp47(NSLocale.currentLocale.localeIdentifier)
    val canonical = locale?.let { NSLocale.canonicalLanguageIdentifierFromString(it) }
    if (canonical.isNullOrEmpty()) return fallback

    val available = NSLocale.availableLocaleIdentifiers.mapNotNullTo(HashSet()) {
        (it as? String)?.let(::bcp47)
    }
    var tag: String = bcp47(canonical)
    while (tag.isNotEmpty()) {
        if (tag in available) return tag
        val cut = tag.lastIndexOf('-')
        if (cut < 0) break
        tag = tag.substring(0, cut)
    }
    return fallback
}

private fun bcp47(identifier: String): String = identifier.replace('_', '-')
