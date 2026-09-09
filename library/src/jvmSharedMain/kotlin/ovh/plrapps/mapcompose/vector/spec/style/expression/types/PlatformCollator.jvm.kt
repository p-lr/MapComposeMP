package ovh.plrapps.mapcompose.vector.spec.style.expression.types

import java.text.Collator as JavaCollator
import java.util.Locale

/**
 * `java.text.Collator`, which is CLDR-backed on desktop and ICU-backed on Android.
 *
 * The four ICU sensitivities map onto three Java strengths:
 *
 * | sensitivity | strength | |
 * |---|---|---|
 * | `base` | `PRIMARY` | letters only |
 * | `accent` | `SECONDARY` | + diacritics |
 * | `variant` | `TERTIARY` | + case |
 * | `case` | `PRIMARY` | + [compareCase], see below |
 *
 * `case` is the one with no Java equivalent: it wants case honoured while diacritics are ignored,
 * which ICU expresses as a primary strength with `caseLevel` on and `java.text` cannot express at
 * all. Comparing at `PRIMARY` and breaking the tie on case reproduces it, and the tie-break has to
 * order lowercase *before* uppercase, as ICU does and raw code points do not.
 *
 * A collator is built per call: `RuleBasedCollator.compare` walks a mutable
 * `CollationElementIterator`, so an instance shared between tile workers would be a data race. See
 * [compareLocalized].
 */
internal actual fun compareLocalized(
    lhs: String,
    rhs: String,
    sensitivity: String,
    locale: String?,
): Int {
    val collator = JavaCollator.getInstance(javaLocale(locale))
    collator.strength = when (sensitivity) {
        "accent" -> JavaCollator.SECONDARY
        "variant" -> JavaCollator.TERTIARY
        else -> JavaCollator.PRIMARY
    }

    val primary = collator.compare(lhs, rhs)
    if (primary != 0 || sensitivity != "case") return primary
    return compareCase(lhs, rhs)
}

/**
 * ECMA-402's lookup matcher: canonicalize, then drop subtags from the right until the tag names a
 * locale the platform has collation data for, and answer with the host default when none does.
 */
internal actual fun resolveLocalePlatform(locale: String?): String {
    val requested = locale?.let { Locale.forLanguageTag(it) }
    if (requested != null && requested.language.isNotEmpty()) {
        val available = JavaCollator.getAvailableLocales().mapTo(HashSet()) { it.toLanguageTag() }
        var tag = requested.toLanguageTag()
        while (tag.isNotEmpty() && tag != "und") {
            if (tag in available) return tag
            val cut = tag.lastIndexOf('-')
            if (cut < 0) break
            tag = tag.substring(0, cut)
        }
    }
    return Locale.getDefault(Locale.Category.FORMAT).toLanguageTag()
}

private fun javaLocale(locale: String?): Locale =
    locale?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault(Locale.Category.FORMAT)

/** ICU orders lowercase before uppercase at the case level; raw code points do the reverse. */
private fun compareCase(lhs: String, rhs: String): Int {
    val n = minOf(lhs.length, rhs.length)
    for (i in 0 until n) {
        val a = if (lhs[i].isUpperCase()) 1 else 0
        val b = if (rhs[i].isUpperCase()) 1 else 0
        if (a != b) return a - b
    }
    return lhs.length - rhs.length
}
