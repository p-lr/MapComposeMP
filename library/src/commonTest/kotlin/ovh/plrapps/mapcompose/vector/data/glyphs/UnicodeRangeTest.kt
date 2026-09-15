package ovh.plrapps.mapcompose.vector.data.glyphs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `unicode-range` grammar a `font-faces` entry borrows from CSS. */
class UnicodeRangeTest {

    private fun range(value: String): UnicodeRange = UnicodeRange.parse(value)!!

    @Test
    fun `a single codepoint is a range of one`() {
        val parsed = range("U+A5")
        assertEquals(0xA5, parsed.start)
        assertEquals(0xA5, parsed.end)
    }

    @Test
    fun `an explicit range carries both bounds`() {
        val parsed = range("U+0-10FFFF")
        assertEquals(0, parsed.start)
        assertEquals(0x10FFFF, parsed.end)
    }

    @Test
    fun `a wildcard covers every codepoint it stands for`() {
        val parsed = range("U+4??")
        assertEquals(0x400, parsed.start)
        assertEquals(0x4FF, parsed.end)
    }

    @Test
    fun `the prefix is lower case too`() {
        val parsed = range("u+1780-17ff")
        assertEquals(0x1780, parsed.start)
        assertEquals(0x17FF, parsed.end)
    }

    @Test
    fun `an end past the last codepoint is clamped`() {
        assertEquals(UnicodeRange.MAX_CODE_POINT, range("U+0-FFFFFF").end)
    }

    @Test
    fun `a start past the last codepoint is no range at all`() {
        assertNull(UnicodeRange.parse("U+200000"))
    }

    @Test
    fun `a backwards range is rejected`() {
        assertNull(UnicodeRange.parse("U+100-0"))
    }

    @Test
    fun `a wildcard wider than six digits is rejected`() {
        assertNull(UnicodeRange.parse("U+1???????"))
    }

    @Test
    fun `anything that is not a range is rejected`() {
        assertNull(UnicodeRange.parse(""))
        assertNull(UnicodeRange.parse("A5"))
        assertNull(UnicodeRange.parse("U+"))
        assertNull(UnicodeRange.parse("U+zz"))
        assertNull(UnicodeRange.parse("U+0-10-20"))
    }

    @Test
    fun `the default range covers everything`() {
        assertTrue(UnicodeRange.DEFAULT.covers(0))
        assertTrue(UnicodeRange.DEFAULT.covers(UnicodeRange.MAX_CODE_POINT))
    }

    @Test
    fun `a list of ranges covers the union of them`() {
        val ranges = listOf(range("U+41"), range("U+1780-17FF"))
        assertTrue(ranges.covers(0x41))
        assertTrue(ranges.covers(0x1790))
        assertFalse(ranges.covers(0x42))
    }
}
