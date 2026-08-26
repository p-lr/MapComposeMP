package ovh.plrapps.mapcompose.vector.spec.style.expression.types

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Collator] has no upstream test file: it is exercised through the `collator` conformance
 * fixtures, 16 of which pass and 3 of which are documented ICU divergences.
 *
 * These assert the three strength levels directly, and pin the divergences so they stay deliberate.
 */
class CollatorTest {

    private fun collator(caseSensitive: Boolean, diacriticSensitive: Boolean, locale: String? = null) =
        Collator(caseSensitive, diacriticSensitive, locale)

    private fun sign(value: Int): Int = when {
        value < 0 -> -1
        value > 0 -> 1
        else -> 0
    }

    // region primary level: diacritics and case folded away

    /**
     * The primary key folds diacritics, which is what puts "ä" next to "a" instead of after "z".
     * Raw code-point order would sort U+00E4 after 'b'.
     */
    @Test
    fun `primary ordering places accented letters next to their base letter`() {
        val base = collator(caseSensitive = false, diacriticSensitive = false)
        assertEquals(-1, sign(base.compare("ä", "b")))
        assertEquals(1, sign(base.compare("b", "ä")))
        assertEquals(0, sign(base.compare("ä", "a")))
        assertEquals(0, sign(base.compare("A", "a")))
        assertEquals(-1, sign(base.compare("a", "b")))
    }

    @Test
    fun `base sensitivity ignores both case and diacritics`() {
        val base = collator(caseSensitive = false, diacriticSensitive = false)
        assertEquals(0, base.compare("résumé", "RESUME"))
        assertEquals(0, base.compare("tēnā", "tena"))
    }

    // endregion

    // region secondary level: diacritics

    @Test
    fun `accent sensitivity distinguishes diacritics but not case`() {
        val accent = collator(caseSensitive = false, diacriticSensitive = true)
        assertEquals(0, accent.compare("A", "a"))
        assertEquals(1, sign(accent.compare("ä", "a")))
        assertEquals(-1, sign(accent.compare("ä", "b")))
        assertTrue(accent.compare("tēnā", "tena") != 0)
    }

    // endregion

    // region tertiary level: case

    /** ICU sorts lowercase before uppercase; raw code points do the reverse. */
    @Test
    fun `case sensitivity sorts lowercase before uppercase`() {
        val case = collator(caseSensitive = true, diacriticSensitive = false)
        assertEquals(-1, sign(case.compare("a", "A")))
        assertEquals(1, sign(case.compare("A", "a")))
        // …but diacritics are still folded away at this sensitivity.
        assertEquals(0, case.compare("a", "ä"))
    }

    @Test
    fun `variant sensitivity distinguishes both`() {
        val variant = collator(caseSensitive = true, diacriticSensitive = true)
        assertEquals(-1, sign(variant.compare("a", "A")))
        assertEquals(-1, sign(variant.compare("a", "ä")))
        assertEquals(0, variant.compare("a", "a"))
    }

    // endregion

    @Test
    fun `the folding table covers Latin Extended-A`() {
        val base = collator(caseSensitive = false, diacriticSensitive = false)
        // Latin-1 Supplement
        assertEquals(0, base.compare("àáâãäå", "aaaaaa"))
        assertEquals(0, base.compare("çñüý", "cnuy"))
        // Latin Extended-A
        assertEquals(0, base.compare("āēīōū", "aeiou"))
        assertEquals(0, base.compare("ŚŻĆ", "szc"))
        // Combining marks are dropped outright.
        assertEquals(0, base.compare("á", "a"))
    }

    /**
     * The folding table is generated from Unicode canonical decomposition, so letters formed with a
     * stroke or bar rather than a diacritic — Ł, Đ, Ø — have no decomposition and are left
     * alone. ICU folds them at primary strength; this port does not.
     */
    @Test
    fun `stroked letters are not folded`() {
        val base = collator(caseSensitive = false, diacriticSensitive = false)
        assertTrue(base.compare("ł", "l") != 0)
        assertTrue(base.compare("ø", "o") != 0)
        assertTrue(base.compare("đ", "d") != 0)
    }

    @Test
    fun `characters outside the table are left alone`() {
        val base = collator(caseSensitive = false, diacriticSensitive = false)
        assertEquals(0, base.compare("日本語", "日本語"))
        assertTrue(base.compare("日本語", "中文") != 0)
    }

    @Test
    fun `resolvedLocale echoes the requested locale`() {
        assertEquals("de", collator(false, false, "de").resolvedLocale())
        assertEquals("en", collator(false, false, null).resolvedLocale())
    }

    @Test
    fun `equality and hashing follow the options`() {
        assertEquals(collator(true, false, "de"), collator(true, false, "de"))
        assertEquals(collator(true, false, "de").hashCode(), collator(true, false, "de").hashCode())
        assertTrue(collator(true, false, "de") != collator(false, false, "de"))
        assertTrue(collator(true, false, "de") != collator(true, false, "en"))
    }

    /**
     * Documented divergences: locale-tailored collation needs ICU, which Kotlin Multiplatform has
     * no equivalent of. These are the three `collator` conformance fixtures listed in
     * `ExpressionConformanceTest.KNOWN_DIVERGENCES`; pinning them here keeps them deliberate.
     */
    @Test
    fun `locale tailoring is not implemented`() {
        // German collation expands "ü" to "ue"; this port compares them as different words.
        assertTrue(collator(true, false, "de").compare("ü", "ue") != 0)
        // Swedish sorts "ä" after "z"; this port sorts it next to "a" in every locale.
        assertEquals(
            sign(collator(false, false, "sv").compare("ä", "a")),
            sign(collator(false, false, "fr").compare("ä", "a")),
        )
    }
}
