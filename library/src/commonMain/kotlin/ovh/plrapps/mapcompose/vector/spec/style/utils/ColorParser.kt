package ovh.plrapps.mapcompose.vector.spec.style.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import ovh.plrapps.mapcompose.vector.spec.style.expression.colorspaces.CssColorParser

/**
 * Parses and formats the colour strings that appear in a MapLibre style.
 *
 * Parsing is [CssColorParser] — a port of MapLibre's CSS Color 4 parser — and nothing else. An
 * earlier hand-rolled parser used to sit behind it as a fallback; it was laxer than the spec (it
 * accepted, for instance, `rgb()` forms with the wrong argument count) and a second, more permissive
 * parser behind the first can only turn invalid input into a colour.
 */
object ColorParser {

    fun parseColorString(color: String): Color =
        parseColorStringOrNull(color) ?: throw ColorParserException("unsupported color $color")

    fun parseColorStringOrNull(color: String): Color? {
        val rgba = CssColorParser.parse(color) ?: return null
        return Color(
            red = rgba[0].toFloat(),
            green = rgba[1].toFloat(),
            blue = rgba[2].toFloat(),
            alpha = rgba[3].toFloat(),
        )
    }

    /** `#RRGGBB`, or `#RRGGBBAA` when the colour is not fully opaque. */
    fun colorToHexString(color: Color): String {
        val argb = color.toArgb()
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val a = (argb shr 24) and 0xFF

        fun toHex(value: Int) = value.toString(16).padStart(2, '0')

        return buildString {
            append("#")
            append(toHex(r))
            append(toHex(g))
            append(toHex(b))
            if (a != 0xFF) {
                append(toHex(a))
            }
        }.uppercase()
    }

    class ColorParserException(message: String) : Throwable(message = message)
}
