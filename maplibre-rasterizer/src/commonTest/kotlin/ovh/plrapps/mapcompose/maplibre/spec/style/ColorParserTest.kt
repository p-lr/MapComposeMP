package ovh.plrapps.mapcompose.maplibre.spec.style

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.maplibre.spec.style.utils.ColorParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class ColorParserTest {
    private val parser = ColorParser

    @Test
    fun `parse should handle hex colors`() {
        assertEquals(Color(0xFF, 0x00, 0x00), parser.parseColorStringOrNull("#F00"))
        assertEquals(Color(0x00, 0xFF, 0x00), parser.parseColorStringOrNull("#0F0"))
        assertEquals(Color(0x00, 0x00, 0xFF), parser.parseColorStringOrNull("#00F"))

        assertEquals(Color(0xFF, 0x00, 0x00), parser.parseColorStringOrNull("#FF0000"))
        assertEquals(Color(0x00, 0xFF, 0x00), parser.parseColorStringOrNull("#00FF00"))
        assertEquals(Color(0x00, 0x00, 0xFF), parser.parseColorStringOrNull("#0000FF"))

        assertEquals(Color(0xFF, 0x00, 0x00, 0x80), parser.parseColorStringOrNull("#FF000080"))
        assertEquals(Color(0x00, 0xFF, 0x00, 0x80), parser.parseColorStringOrNull("#00FF0080"))
        assertEquals(Color(0x00, 0x00, 0xFF, 0x80), parser.parseColorStringOrNull("#0000FF80"))

        assertNull(parser.parseColorStringOrNull("#"))
        assertNull(parser.parseColorStringOrNull("#12345"))
        assertNull(parser.parseColorStringOrNull("#GGGGGG"))
    }

    @Test
    fun `parse should handle rgb colors`() {
        assertEquals(Color(255, 0, 0), parser.parseColorStringOrNull("rgb(255, 0, 0)"))
        assertEquals(Color(0, 255, 0), parser.parseColorStringOrNull("rgb(0, 255, 0)"))
        assertEquals(Color(0, 0, 255), parser.parseColorStringOrNull("rgb(0, 0, 255)"))
        assertEquals(Color(0xFF6495ED), parser.parseColorStringOrNull("cornflowerblue"))
        assertEquals(Color(0xFF000000), parser.parseColorStringOrNull("black"))
        assertEquals(Color(0xFFB22222), parser.parseColorStringOrNull("firebrick"))

        assertNull(parser.parseColorStringOrNull("rgb()"))
        assertNull(parser.parseColorStringOrNull("rgb(255)"))
        assertNull(parser.parseColorStringOrNull("rgb(255, 0)"))
        assertNull(parser.parseColorStringOrNull("rgb(255, 0, 0, 0)"))
    }

    @Test
    fun `parse should handle rgba colors`() {
        assertEquals(Color(255, 0, 0, 128), parser.parseColorStringOrNull("rgba(255, 0, 0, 0.5)"))
        assertEquals(Color(0, 255, 0, 128), parser.parseColorStringOrNull("rgba(0, 255, 0, 0.5)"))
        assertEquals(Color(0, 0, 255, 128), parser.parseColorStringOrNull("rgba(0, 0, 255, 0.5)"))

        // wrong RGBA colors
        assertNull(parser.parseColorStringOrNull("rgba()"))
        assertNull(parser.parseColorStringOrNull("rgba(255)"))
        assertNull(parser.parseColorStringOrNull("rgba(255, 0)"))
        assertNull(parser.parseColorStringOrNull("rgba(255, 0, 0)"))
    }

    @Test
    fun `parse should handle hsl colors`() {
        assertEquals(Color(255, 0, 0), parser.parseColorStringOrNull("hsl(0, 100%, 50%)"))
        assertEquals(Color(0, 255, 0), parser.parseColorStringOrNull("hsl(120, 100%, 50%)"))
        assertEquals(Color(0, 0, 255), parser.parseColorStringOrNull("hsl(240, 100%, 50%)"))

        assertNull(parser.parseColorStringOrNull("hsl()"))
        assertNull(parser.parseColorStringOrNull("hsl(0)"))
        assertNull(parser.parseColorStringOrNull("hsl(0, 100%)"))
        assertNull(parser.parseColorStringOrNull("hsl(0, 100%, 50%, 0.5)"))
    }

    @Test
    fun `parse should handle hsla colors`() {
        assertEquals(Color(255, 0, 0, 128), parser.parseColorStringOrNull("hsla(0, 100%, 50%, 0.5)"))
        assertEquals(Color(0, 255, 0, 128), parser.parseColorStringOrNull("hsla(120, 100%, 50%, 0.5)"))
        assertEquals(Color(0, 0, 255, 128), parser.parseColorStringOrNull("hsla(240, 100%, 50%, 0.5)"))

        assertNull(parser.parseColorStringOrNull("hsla()"))
        assertNull(parser.parseColorStringOrNull("hsla(0)"))
        assertNull(parser.parseColorStringOrNull("hsla(0, 100%)"))
        assertNull(parser.parseColorStringOrNull("hsla(0, 100%, 50%)"))
    }

    @Test
    fun `parse should handle null and invalid input`() {
        assertNull(parser.parseColorStringOrNull(""))
        assertNull(parser.parseColorStringOrNull("invalid"))
        assertNull(parser.parseColorStringOrNull("color(255, 0, 0)"))
    }

    @Test
    fun `parse should handle hsl color`() {
        val color = ColorParser.parseColorStringOrNull("hsl(75,51%,85%)")
        assertNotNull(color)
        assertEquals(Color.hsl(hue = 75F, saturation = 0.51f, lightness = 0.85f), color)
    }

    @Test
    fun `parse should handle hsla color`() {
        val color = ColorParser.parseColorStringOrNull("hsla(9, 100%, 64%, 0.4)")
        assertNotNull(color)
        assertEquals(Color.hsl(hue = 9F, saturation = 1f, lightness = 0.64f, alpha = 0.4f), color)
    }
} 