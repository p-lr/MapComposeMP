package ovh.plrapps.mapcompose.vector.spec.style.expression.types

/**
 * Ported from `maplibre-style-spec/src/expression/types/collator.ts`.
 *
 * Upstream builds an `Intl.Collator` from [sensitivity] and [locale] and delegates both methods to
 * it. So does this, through [compareLocalized] and [resolveLocalePlatform]: `Intl.Collator` on wasm,
 * `NSString.compare(_:options:range:locale:)` on iOS, `java.text.Collator` on Android and desktop.
 * Locale tailoring is therefore real -- Swedish "ä" sorts after "z" on every target, where this used
 * to be a hand-rolled fold table that put it next to "a" in every locale.
 *
 * Three things still differ from upstream, all of them ICU surface a platform does not expose:
 *
 * - **`usage: 'search'` tailorings are wasm-only.** Upstream builds its collator with them, and they
 *   are what make German "ü" equal "ue" and German "ä" a primary-distinct letter. Neither
 *   `java.text.Collator` nor Foundation can ask for the `search` collation, so those two comparisons
 *   answer by the standard German collation there. The three `collator` fixtures listed in
 *   `ExpressionConformanceTest.KNOWN_DIVERGENCES` are exactly this.
 * - **`java.text` has no case level**, so `case` sensitivity is a `PRIMARY` comparison with a
 *   case tie-break; see the JVM actual.
 * - **Foundation has no strength setting**, so `base` folds diacritics rather than demoting them,
 *   and an accented *letter* reads as its base at that sensitivity on iOS; see the iOS actual.
 */
class Collator(
    private val caseSensitive: Boolean,
    private val diacriticSensitive: Boolean,
    val locale: String?,
) {
    val sensitivity: String = if (caseSensitive) {
        if (diacriticSensitive) "variant" else "case"
    } else {
        if (diacriticSensitive) "accent" else "base"
    }

    fun compare(lhs: String, rhs: String): Int = compareLocalized(lhs, rhs, sensitivity, locale)

    fun resolvedLocale(): String = resolveLocalePlatform(locale)

    override fun equals(other: Any?): Boolean = other is Collator &&
            other.caseSensitive == caseSensitive &&
            other.diacriticSensitive == diacriticSensitive &&
            other.locale == locale

    override fun hashCode(): Int {
        var result = caseSensitive.hashCode()
        result = 31 * result + diacriticSensitive.hashCode()
        result = 31 * result + (locale?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "collator($sensitivity, ${locale ?: ""})"
}
