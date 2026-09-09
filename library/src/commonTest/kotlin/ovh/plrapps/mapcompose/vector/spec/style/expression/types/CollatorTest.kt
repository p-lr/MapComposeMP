package ovh.plrapps.mapcompose.vector.spec.style.expression.types

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Collator] has no upstream test file: it is exercised through the `collator` conformance
 * fixtures, 16 of which pass and 3 of which are documented ICU divergences.
 *
 * Collation itself belongs to each platform's ICU now, so every assertion here is one the four
 * targets agree on -- `Intl.Collator`, `java.text.Collator` on the JVM and the Android host, and
 * Foundation's `NSString.compare` all give the values below. What they do *not* agree on is
 * deliberately absent, and listed in [Collator]'s KDoc: German "ü" against "ue", Swedish "ä" against
 * "a", and stroked letters such as "ł" against "l" at base sensitivity.
 */
class CollatorTest {

    private fun collator(caseSensitive: Boolean, diacriticSensitive: Boolean, locale: String? = null) =
        Collator(caseSensitive, diacriticSensitive, locale)

    private fun sign(value: Int): Int = when {
        value < 0 -> -1
        value > 0 -> 1
        else -> 0
    }

    // region the four sensitivities, against a locale with no tailoring of its own

    @Test
    fun `base sensitivity ignores both case and diacritics`() {
        val base = collator(caseSensitive = false, diacriticSensitive = false, locale = "en")
        assertEquals(0, sign(base.compare("a", "A")))
        assertEquals(0, sign(base.compare("a", "ä")))
        assertEquals(0, sign(base.compare("résumé", "RESUME")))
        assertEquals(0, sign(base.compare("tēnā", "tena")))
        assertEquals(-1, sign(base.compare("a", "b")))
        // Folding is what puts "ä" before "b"; raw code-point order would put U+00E4 after it.
        assertEquals(-1, sign(base.compare("ä", "b")))
    }

    @Test
    fun `accent sensitivity distinguishes diacritics but not case`() {
        val accent = collator(caseSensitive = false, diacriticSensitive = true, locale = "en")
        assertEquals(0, sign(accent.compare("A", "a")))
        assertEquals(1, sign(accent.compare("ä", "a")))
        assertEquals(-1, sign(accent.compare("ä", "b")))
        assertTrue(accent.compare("tēnā", "tena") != 0)
    }

    /** ICU sorts lowercase before uppercase; raw code points do the reverse. */
    @Test
    fun `case sensitivity sorts lowercase before uppercase`() {
        val case = collator(caseSensitive = true, diacriticSensitive = false, locale = "en")
        assertEquals(-1, sign(case.compare("a", "A")))
        assertEquals(1, sign(case.compare("A", "a")))
        // …but diacritics are still folded away at this sensitivity.
        assertEquals(0, sign(case.compare("a", "ä")))
    }

    @Test
    fun `variant sensitivity distinguishes both`() {
        val variant = collator(caseSensitive = true, diacriticSensitive = true, locale = "en")
        assertEquals(-1, sign(variant.compare("a", "A")))
        assertEquals(-1, sign(variant.compare("a", "ä")))
        assertEquals(1, sign(variant.compare("b", "ä")))
        assertEquals(0, sign(variant.compare("a", "a")))
    }

    // endregion

    /**
     * The finding this platform route exists for. Swedish treats "ä" as a letter in its own right,
     * placed after "z"; the hand-rolled fold table this replaced sorted it next to "a" in every
     * locale, so a Swedish-collated filter picked the wrong features.
     */
    @Test
    fun `Swedish sorts a-umlaut after z`() {
        val swedish = collator(caseSensitive = false, diacriticSensitive = false, locale = "sv")
        assertEquals(1, sign(swedish.compare("ä", "z")))
        assertEquals(1, sign(swedish.compare("ö", "z")))
        // English has no such tailoring, which is what makes the comparison locale-dependent.
        val english = collator(caseSensitive = false, diacriticSensitive = false, locale = "en")
        assertEquals(-1, sign(english.compare("ä", "z")))
    }

    @Test
    fun `characters outside any Latin tailoring are left alone`() {
        val base = collator(caseSensitive = false, diacriticSensitive = false, locale = "en")
        assertEquals(0, sign(base.compare("日本語", "日本語")))
        assertTrue(base.compare("日本語", "中文") != 0)
    }

    /**
     * A tag the platform has collation data for resolves to itself. A tag it does not, and an absent
     * locale, resolve to the host default, which is what `Intl.Collator` does and so is not a fixed
     * string to assert against.
     */
    @Test
    fun `resolvedLocale resolves an available tag to itself`() {
        assertEquals("de", collator(false, false, "de").resolvedLocale())
        assertEquals("en", collator(false, false, "en").resolvedLocale())
        assertTrue(collator(false, false, null).resolvedLocale().isNotEmpty())
    }

    @Test
    fun `equality and hashing follow the options`() {
        assertEquals(collator(true, false, "de"), collator(true, false, "de"))
        assertEquals(collator(true, false, "de").hashCode(), collator(true, false, "de").hashCode())
        assertTrue(collator(true, false, "de") != collator(false, false, "de"))
        assertTrue(collator(true, false, "de") != collator(true, false, "en"))
    }
}
