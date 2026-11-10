package ovh.plrapps.mapcompose.vector.spec.style

import ovh.plrapps.mapcompose.vector.data.decodeStyle
import ovh.plrapps.mapcompose.vector.spec.style.utils.StyleDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The style's root `font-faces` block, in the two shapes the specification writes it in.
 *
 * A malformed entry is reported rather than thrown: a decode that throws leaves
 * `getMapLibreConfiguration` returning a failure, which blanks the whole map over one bad font.
 */
class FontFacesStyleTest {

    private fun style(fontFaces: String): MapLibreStyle = decodeStyle(
        """{"version":8,"sources":{},"layers":[],"font-faces":$fontFaces}"""
    )

    @Test
    fun `a style declaring nothing has no font faces`() {
        val decoded = decodeStyle("""{"version":8,"sources":{},"layers":[]}""")
        assertTrue(decoded.fontFaces.isEmpty())
        assertTrue(!decoded.hasFontFaces)
    }

    @Test
    fun `a bare url covers every codepoint`() {
        val decoded = style("""{"Noto Sans Regular":"https://example.com/noto.ttf"}""")
        val face = decoded.fontFaces.single()
        assertEquals("Noto Sans Regular", face.fontName)
        assertEquals("https://example.com/noto.ttf", face.url)
        assertTrue(face.unicodeRange.isEmpty())
    }

    @Test
    fun `an object carries its unicode ranges`() {
        val decoded = style(
            """{"Noto Sans Regular":{"url":"https://example.com/khmer.ttf","unicode-range":["U+1780-17FF","U+200C"]}}"""
        )
        val face = decoded.fontFaces.single()
        assertEquals(listOf("U+1780-17FF", "U+200C"), face.unicodeRange)
    }

    @Test
    fun `a list keeps every file and its order`() {
        val decoded = style(
            """
            {"Noto Sans Regular":[
              {"url":"https://example.com/khmer.ttf","unicode-range":["U+1780-17FF"]},
              "https://example.com/noto.ttf"
            ]}
            """.trimIndent()
        )
        assertEquals(
            listOf("https://example.com/khmer.ttf", "https://example.com/noto.ttf"),
            decoded.fontFaces.map { it.url },
        )
    }

    @Test
    fun `several names each keep their own files`() {
        val decoded = style(
            """{"A":"https://example.com/a.ttf","B":"https://example.com/b.ttf"}"""
        )
        assertEquals(listOf("A", "B"), decoded.fontFaces.map { it.fontName })
    }

    @Test
    fun `an entry with no url is reported and skipped`() {
        StyleDiagnostics.drain()
        val decoded = style("""{"Noto Sans Regular":{"unicode-range":["U+0-FF"]}}""")
        assertTrue(decoded.fontFaces.isEmpty())
        assertTrue(StyleDiagnostics.drain().any { it.location == "font-faces" })
    }

    @Test
    fun `a declaration of the wrong shape is reported and skipped`() {
        StyleDiagnostics.drain()
        val decoded = style("""{"Noto Sans Regular":[42]}""")
        assertTrue(decoded.fontFaces.isEmpty())
        assertTrue(StyleDiagnostics.drain().any { it.location == "font-faces" })
    }

    @Test
    fun `the block survives a style that also declares glyphs`() {
        val decoded = decodeStyle(
            """
            {"version":8,"sources":{},"layers":[],
             "glyphs":"https://example.com/{fontstack}/{range}.pbf",
             "font-faces":{"Noto Sans Regular":"https://example.com/noto.ttf"}}
            """.trimIndent()
        )
        assertEquals("https://example.com/{fontstack}/{range}.pbf", decoded.glyphs)
        assertEquals(1, decoded.fontFaces.size)
    }
}
