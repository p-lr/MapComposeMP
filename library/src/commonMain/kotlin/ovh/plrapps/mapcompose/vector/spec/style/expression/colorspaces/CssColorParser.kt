package ovh.plrapps.mapcompose.vector.spec.style.expression.colorspaces

import kotlin.math.max
import kotlin.math.min

/**
 * CSS color parser compliant with the CSS Color 4 specification.
 *
 * Ported from `maplibre-style-spec/src/expression/types/parse_css_color.ts`. Supports named colors,
 * the `transparent` keyword, all rgb hex notations, and the `rgb()` / `rgba()` / `hsl()` / `hsla()`
 * functions in both the comma and the space-separated grammar. Values are **not** rounded to
 * integers.
 *
 * Caveats carried over from upstream:
 *  - `<angle>` is a number with an optional `deg` suffix; `grad`, `rad` and `turn` are not supported
 *  - the `none` keyword is not supported
 *  - comments inside `rgb()` / `hsl()` are not supported
 *  - `rgba()` and `hsla()` are accepted with grammar and behaviour identical to `rgb()` / `hsl()`
 *
 * @return `[r, g, b, a]` in sRGB with all channels in 0..1, or `null` if the input is not a color.
 */
object CssColorParser {

    fun parse(inputRaw: String): RgbColor? {
        val input = inputRaw.lowercase().trim()

        if (input == "transparent") return doubleArrayOf(0.0, 0.0, 0.0, 0.0)

        namedColors[input]?.let { (r, g, b) ->
            return doubleArrayOf(r / 255.0, g / 255.0, b / 255.0, 1.0)
        }

        // #f0c, #f0cf, #ff00cc, #ff00ccff
        if (input.startsWith("#")) {
            if (HEX_REGEX.matches(input)) {
                val step = if (input.length < 6) 1 else 2
                var i = 1
                fun next(): String {
                    val slice = input.substring(i, minOf(i + step, input.length))
                    i += step
                    return slice
                }
                return doubleArrayOf(
                    parseHex(next()), parseHex(next()), parseHex(next()),
                    parseHex(input.substring(minOf(i, input.length), minOf(i + step, input.length)).ifEmpty { "ff" }),
                )
            }
        }

        // rgb(128 0 0), rgb(50% 0% 0%), rgba(255,0,255,0.6), rgb(255 0 255 / 60%)
        if (input.startsWith("rgb")) {
            val rgbMatch = RGB_REGEX.matchEntire(input) ?: return null
            val g = rgbMatch.groupValues
            val (r, rp, f1) = Triple(g[1], g[2], g[3])
            val (gr, gp, f2) = Triple(g[4], g[5], g[6])
            val (b, bp, f3) = Triple(g[7], g[8], g[9])
            val a = g[10]
            val ap = g[11]

            val argFormat = (f1.ifEmpty { " " }) + (f2.ifEmpty { " " }) + f3
            if (argFormat !in VALID_ARG_FORMATS) return null

            val valFormat = rp + gp + bp
            val maxValue = when (valFormat) {
                "%%%" -> 100.0
                "" -> 255.0
                else -> return null // values must be all numbers or all percentages
            }

            val rgba = doubleArrayOf(
                clamp(jsNumber(r) / maxValue, 0.0, 1.0),
                clamp(jsNumber(gr) / maxValue, 0.0, 1.0),
                clamp(jsNumber(b) / maxValue, 0.0, 1.0),
                if (a.isNotEmpty()) parseAlpha(jsNumber(a), ap) else 1.0,
            )
            return if (rgba.none { it.isNaN() }) rgba else null
        }

        // hsl(120 50% 80%), hsla(120deg,50%,80%,.9), hsl(12e1 50% 80% / 90%)
        val hslMatch = HSL_REGEX.matchEntire(input) ?: return null
        val g = hslMatch.groupValues
        val h = g[1]
        val f1 = g[2]
        val s = g[3]
        val f2 = g[4]
        val l = g[5]
        val f3 = g[6]
        val a = g[7]
        val ap = g[8]

        val argFormat = (f1.ifEmpty { " " }) + (f2.ifEmpty { " " }) + f3
        if (argFormat !in VALID_ARG_FORMATS) return null

        val hsla = doubleArrayOf(
            jsNumber(h),
            clamp(jsNumber(s), 0.0, 100.0),
            clamp(jsNumber(l), 0.0, 100.0),
            if (a.isNotEmpty()) parseAlpha(jsNumber(a), ap) else 1.0,
        )
        return if (hsla.none { it.isNaN() }) hslToRgb(hsla) else null
    }

    private val VALID_ARG_FORMATS = setOf("  ", "  /", ",,", ",,,")

    private val HEX_REGEX = Regex("^#(?:[0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})$")

    private val RGB_REGEX = Regex(
        """^rgba?\(\s*([\de.+-]+)(%)?(?:\s+|\s*(,)\s*)([\de.+-]+)(%)?(?:\s+|\s*(,)\s*)([\de.+-]+)(%)?(?:\s*([,/])\s*([\de.+-]+)(%)?)?\s*\)$"""
    )

    private val HSL_REGEX = Regex(
        """^hsla?\(\s*([\de.+-]+)(?:deg)?(?:\s+|\s*(,)\s*)([\de.+-]+)%(?:\s+|\s*(,)\s*)([\de.+-]+)%(?:\s*([,/])\s*([\de.+-]+)(%)?)?\s*\)$"""
    )

    /** `#f0c` expands each digit, so the slice is padded with itself before parsing. */
    private fun parseHex(hex: String): Double {
        val padded = if (hex.length == 1) hex + hex else hex
        return (padded.toIntOrNull(16) ?: return Double.NaN) / 255.0
    }

    private fun parseAlpha(a: Double, asPercentage: String): Double =
        clamp(if (asPercentage.isNotEmpty()) a / 100.0 else a, 0.0, 1.0)

    private fun clamp(n: Double, minValue: Double, maxValue: Double): Double = min(max(minValue, n), maxValue)

    /**
     * The numeric part of the regexes is loose, so a match may still not be a valid number.
     * Mirrors JavaScript's unary `+`: anything unparseable becomes NaN and is rejected.
     */
    private fun jsNumber(s: String): Double = s.toDoubleOrNull() ?: Double.NaN

    // 147 CSS Color 4 named colors
    private val namedColors: Map<String, IntArray> = mapOf(
        "aliceblue" to intArrayOf(240, 248, 255),
        "antiquewhite" to intArrayOf(250, 235, 215),
        "aqua" to intArrayOf(0, 255, 255),
        "aquamarine" to intArrayOf(127, 255, 212),
        "azure" to intArrayOf(240, 255, 255),
        "beige" to intArrayOf(245, 245, 220),
        "bisque" to intArrayOf(255, 228, 196),
        "black" to intArrayOf(0, 0, 0),
        "blanchedalmond" to intArrayOf(255, 235, 205),
        "blue" to intArrayOf(0, 0, 255),
        "blueviolet" to intArrayOf(138, 43, 226),
        "brown" to intArrayOf(165, 42, 42),
        "burlywood" to intArrayOf(222, 184, 135),
        "cadetblue" to intArrayOf(95, 158, 160),
        "chartreuse" to intArrayOf(127, 255, 0),
        "chocolate" to intArrayOf(210, 105, 30),
        "coral" to intArrayOf(255, 127, 80),
        "cornflowerblue" to intArrayOf(100, 149, 237),
        "cornsilk" to intArrayOf(255, 248, 220),
        "crimson" to intArrayOf(220, 20, 60),
        "cyan" to intArrayOf(0, 255, 255),
        "darkblue" to intArrayOf(0, 0, 139),
        "darkcyan" to intArrayOf(0, 139, 139),
        "darkgoldenrod" to intArrayOf(184, 134, 11),
        "darkgray" to intArrayOf(169, 169, 169),
        "darkgreen" to intArrayOf(0, 100, 0),
        "darkgrey" to intArrayOf(169, 169, 169),
        "darkkhaki" to intArrayOf(189, 183, 107),
        "darkmagenta" to intArrayOf(139, 0, 139),
        "darkolivegreen" to intArrayOf(85, 107, 47),
        "darkorange" to intArrayOf(255, 140, 0),
        "darkorchid" to intArrayOf(153, 50, 204),
        "darkred" to intArrayOf(139, 0, 0),
        "darksalmon" to intArrayOf(233, 150, 122),
        "darkseagreen" to intArrayOf(143, 188, 143),
        "darkslateblue" to intArrayOf(72, 61, 139),
        "darkslategray" to intArrayOf(47, 79, 79),
        "darkslategrey" to intArrayOf(47, 79, 79),
        "darkturquoise" to intArrayOf(0, 206, 209),
        "darkviolet" to intArrayOf(148, 0, 211),
        "deeppink" to intArrayOf(255, 20, 147),
        "deepskyblue" to intArrayOf(0, 191, 255),
        "dimgray" to intArrayOf(105, 105, 105),
        "dimgrey" to intArrayOf(105, 105, 105),
        "dodgerblue" to intArrayOf(30, 144, 255),
        "firebrick" to intArrayOf(178, 34, 34),
        "floralwhite" to intArrayOf(255, 250, 240),
        "forestgreen" to intArrayOf(34, 139, 34),
        "fuchsia" to intArrayOf(255, 0, 255),
        "gainsboro" to intArrayOf(220, 220, 220),
        "ghostwhite" to intArrayOf(248, 248, 255),
        "gold" to intArrayOf(255, 215, 0),
        "goldenrod" to intArrayOf(218, 165, 32),
        "gray" to intArrayOf(128, 128, 128),
        "green" to intArrayOf(0, 128, 0),
        "greenyellow" to intArrayOf(173, 255, 47),
        "grey" to intArrayOf(128, 128, 128),
        "honeydew" to intArrayOf(240, 255, 240),
        "hotpink" to intArrayOf(255, 105, 180),
        "indianred" to intArrayOf(205, 92, 92),
        "indigo" to intArrayOf(75, 0, 130),
        "ivory" to intArrayOf(255, 255, 240),
        "khaki" to intArrayOf(240, 230, 140),
        "lavender" to intArrayOf(230, 230, 250),
        "lavenderblush" to intArrayOf(255, 240, 245),
        "lawngreen" to intArrayOf(124, 252, 0),
        "lemonchiffon" to intArrayOf(255, 250, 205),
        "lightblue" to intArrayOf(173, 216, 230),
        "lightcoral" to intArrayOf(240, 128, 128),
        "lightcyan" to intArrayOf(224, 255, 255),
        "lightgoldenrodyellow" to intArrayOf(250, 250, 210),
        "lightgray" to intArrayOf(211, 211, 211),
        "lightgreen" to intArrayOf(144, 238, 144),
        "lightgrey" to intArrayOf(211, 211, 211),
        "lightpink" to intArrayOf(255, 182, 193),
        "lightsalmon" to intArrayOf(255, 160, 122),
        "lightseagreen" to intArrayOf(32, 178, 170),
        "lightskyblue" to intArrayOf(135, 206, 250),
        "lightslategray" to intArrayOf(119, 136, 153),
        "lightslategrey" to intArrayOf(119, 136, 153),
        "lightsteelblue" to intArrayOf(176, 196, 222),
        "lightyellow" to intArrayOf(255, 255, 224),
        "lime" to intArrayOf(0, 255, 0),
        "limegreen" to intArrayOf(50, 205, 50),
        "linen" to intArrayOf(250, 240, 230),
        "magenta" to intArrayOf(255, 0, 255),
        "maroon" to intArrayOf(128, 0, 0),
        "mediumaquamarine" to intArrayOf(102, 205, 170),
        "mediumblue" to intArrayOf(0, 0, 205),
        "mediumorchid" to intArrayOf(186, 85, 211),
        "mediumpurple" to intArrayOf(147, 112, 219),
        "mediumseagreen" to intArrayOf(60, 179, 113),
        "mediumslateblue" to intArrayOf(123, 104, 238),
        "mediumspringgreen" to intArrayOf(0, 250, 154),
        "mediumturquoise" to intArrayOf(72, 209, 204),
        "mediumvioletred" to intArrayOf(199, 21, 133),
        "midnightblue" to intArrayOf(25, 25, 112),
        "mintcream" to intArrayOf(245, 255, 250),
        "mistyrose" to intArrayOf(255, 228, 225),
        "moccasin" to intArrayOf(255, 228, 181),
        "navajowhite" to intArrayOf(255, 222, 173),
        "navy" to intArrayOf(0, 0, 128),
        "oldlace" to intArrayOf(253, 245, 230),
        "olive" to intArrayOf(128, 128, 0),
        "olivedrab" to intArrayOf(107, 142, 35),
        "orange" to intArrayOf(255, 165, 0),
        "orangered" to intArrayOf(255, 69, 0),
        "orchid" to intArrayOf(218, 112, 214),
        "palegoldenrod" to intArrayOf(238, 232, 170),
        "palegreen" to intArrayOf(152, 251, 152),
        "paleturquoise" to intArrayOf(175, 238, 238),
        "palevioletred" to intArrayOf(219, 112, 147),
        "papayawhip" to intArrayOf(255, 239, 213),
        "peachpuff" to intArrayOf(255, 218, 185),
        "peru" to intArrayOf(205, 133, 63),
        "pink" to intArrayOf(255, 192, 203),
        "plum" to intArrayOf(221, 160, 221),
        "powderblue" to intArrayOf(176, 224, 230),
        "purple" to intArrayOf(128, 0, 128),
        "rebeccapurple" to intArrayOf(102, 51, 153),
        "red" to intArrayOf(255, 0, 0),
        "rosybrown" to intArrayOf(188, 143, 143),
        "royalblue" to intArrayOf(65, 105, 225),
        "saddlebrown" to intArrayOf(139, 69, 19),
        "salmon" to intArrayOf(250, 128, 114),
        "sandybrown" to intArrayOf(244, 164, 96),
        "seagreen" to intArrayOf(46, 139, 87),
        "seashell" to intArrayOf(255, 245, 238),
        "sienna" to intArrayOf(160, 82, 45),
        "silver" to intArrayOf(192, 192, 192),
        "skyblue" to intArrayOf(135, 206, 235),
        "slateblue" to intArrayOf(106, 90, 205),
        "slategray" to intArrayOf(112, 128, 144),
        "slategrey" to intArrayOf(112, 128, 144),
        "snow" to intArrayOf(255, 250, 250),
        "springgreen" to intArrayOf(0, 255, 127),
        "steelblue" to intArrayOf(70, 130, 180),
        "tan" to intArrayOf(210, 180, 140),
        "teal" to intArrayOf(0, 128, 128),
        "thistle" to intArrayOf(216, 191, 216),
        "tomato" to intArrayOf(255, 99, 71),
        "turquoise" to intArrayOf(64, 224, 208),
        "violet" to intArrayOf(238, 130, 238),
        "wheat" to intArrayOf(245, 222, 179),
        "white" to intArrayOf(255, 255, 255),
        "whitesmoke" to intArrayOf(245, 245, 245),
        "yellow" to intArrayOf(255, 255, 0),
    )
}

/** `[h, s, l, alpha]` with hue in degrees, saturation and lightness as percentages. */
private fun hslToRgb(hsl: DoubleArray): RgbColor {
    var h = hsl[0] % 360.0
    if (h < 0) h += 360.0
    val s = hsl[1] / 100.0
    val l = hsl[2] / 100.0

    fun f(n: Double): Double {
        val k = (n + h / 30.0) % 12.0
        val a = s * min(l, 1 - l)
        return l - a * max(-1.0, min(min(k - 3, 9 - k), 1.0))
    }

    return doubleArrayOf(f(0.0), f(8.0), f(4.0), hsl[3])
}
