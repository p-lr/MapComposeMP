package ovh.plrapps.mapcompose.vector.spec.style.expression.colorspaces

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Transcribed from `maplibre-style-spec/src/expression/types/parse_css_color.test.ts`.
 *
 * One adaptation: upstream's `should parse valid hsl values` mocks `hslToRgb` so it can inspect the
 * parsed HSL triple before conversion. Kotlin has no equivalent, so the same ground is covered by
 * asserting that every equivalent spelling converts to the same RGB, and by comparing the clamping
 * cases against [hslToRgb] applied to the expected triple directly.
 */
class CssColorParserUpstreamTest {

    private fun parse(input: String): RgbColor? = CssColorParser.parse(input)

    private fun assertRgb(expected: DoubleArray, actual: RgbColor?, tolerance: Double = 1e-12) {
        assertTrue(actual != null, "expected a colour, got null")
        assertTrue(
            expected.indices.all { abs(expected[it] - actual[it]) <= tolerance },
            "expected ${expected.toList()}, got ${actual.toList()}",
        )
    }

    private fun assertSame(a: String, b: String) {
        val left = parse(a)
        val right = parse(b)
        assertTrue(left != null, "'$a' did not parse")
        assertTrue(right != null, "'$b' did not parse")
        assertTrue(
            left.indices.all { abs(left[it] - right[it]) <= 1e-12 },
            "'$a' -> ${left.toList()} but '$b' -> ${right.toList()}",
        )
    }

    // region colour keywords

    @Test
    fun `should parse valid color names`() {
        assertRgb(doubleArrayOf(1.0, 1.0, 1.0, 1.0), parse("white"))
        assertRgb(doubleArrayOf(0.0, 0.0, 0.0, 1.0), parse("black"))
        assertRgb(doubleArrayOf(1.0, 0.0, 0.0, 1.0), parse("RED"))
        assertRgb(doubleArrayOf(127 / 255.0, 255 / 255.0, 212 / 255.0, 1.0), parse("AquaMarine"))
        assertRgb(doubleArrayOf(70 / 255.0, 130 / 255.0, 180 / 255.0, 1.0), parse("steelblue"))
        assertRgb(doubleArrayOf(0.4, 0.2, 0.6, 1.0), parse("rebeccapurple"))
    }

    @Test
    fun `should parse transparent keyword as transparent black`() {
        for (input in listOf("transparent", "Transparent", "TRANSPARENT")) {
            assertRgb(doubleArrayOf(0.0, 0.0, 0.0, 0.0), parse(input))
        }
    }

    @Test
    fun `should return null when provided with invalid color name`() {
        // `__proto__` and `valueOf` are the prototype-chain cases upstream guards with getOwn;
        // a Kotlin Map has no prototype chain, so they are simply absent keys.
        for (input in listOf(
            "not a color name", "", "blak", "aqua-marine", "aqua_marine", "aqua marine",
            "__proto__", "valueOf",
        )) {
            assertNull(parse(input), "'$input' should not parse")
        }
    }

    // endregion

    // region hexadecimal notations

    @Test
    fun `should parse valid rgb hex values`() {
        // hex 3
        assertRgb(doubleArrayOf(1.0, 1.0, 1.0, 1.0), parse("#fff"))
        assertRgb(doubleArrayOf(0.0, 0.0, 0.0, 1.0), parse("#000"))
        assertRgb(doubleArrayOf(0.2, 0.4, 0.6, 1.0), parse("#369"))

        // hex 4
        assertRgb(doubleArrayOf(1.0, 1.0, 1.0, 1.0), parse("#ffff"))
        assertRgb(doubleArrayOf(1.0, 1.0, 1.0, 0.0), parse("#fff0"))
        assertRgb(doubleArrayOf(0.0, 0.0, 0.0, 0.0), parse("#0000"))
        assertRgb(doubleArrayOf(1.0, 1.0, 1.0, 0.8), parse("#FFFC"))
        assertRgb(doubleArrayOf(34 / 255.0, 51 / 255.0, 68 / 255.0, 2 / 3.0), parse("#234a"))

        // hex 6
        assertRgb(doubleArrayOf(1.0, 1.0, 1.0, 1.0), parse("#ffffff"))
        assertRgb(doubleArrayOf(0.0, 0.0, 0.0, 1.0), parse("#000000"))
        assertRgb(doubleArrayOf(0.0, 128 / 255.0, 0.0, 1.0), parse("#008000"))
        assertRgb(doubleArrayOf(185 / 255.0, 103 / 255.0, 16 / 255.0, 1.0), parse("#b96710"))

        // hex 8
        assertRgb(doubleArrayOf(1.0, 1.0, 1.0, 1.0), parse("#ffffffff"))
        assertRgb(doubleArrayOf(0.0, 0.0, 0.0, 1.0), parse("#000000ff"))
        assertRgb(doubleArrayOf(0.0, 0.0, 0.0, 0.0), parse("#00000000"))
        assertRgb(doubleArrayOf(255 / 255.0, 204 / 255.0, 153 / 255.0, 0.2), parse("#FFCc9933"))
        assertRgb(doubleArrayOf(70 / 255.0, 130 / 255.0, 180 / 255.0, 0.4), parse("#4682B466"))
    }

    @Test
    fun `should return null when provided with invalid rgb hex value`() {
        for (input in listOf(
            "#", "#f", "#ff", "#ffg", "#fffg", "#fffff", "#fffffg", "#fffffff", "#fffffffg",
            "#fffffffff", "fff", "# fff",
        )) {
            assertNull(parse(input), "'$input' should not parse")
        }
    }

    // endregion

    // region rgb() and rgba()

    @Test
    fun `should parse valid rgb values`() {
        // rgb 0..255
        assertRgb(doubleArrayOf(0.0, 0.2, 0.0, 1.0), parse("rgb(0 51 0)"))
        for (equivalent in listOf(
            "rgb(0, 51, 0)", "rgb(0.0, 51.0, +0.0)", "rgba(0, 51, 0)", "rgba(0, 51, 0, 1)",
            "rgba(0, 51, 0, 100%)", "rgba( 0, 51, 0, 100% )", "rgba( 00 ,51 ,0 ,100% )",
            " rgb(.0 51 0 / 1)", "rgb(0.0 51.0 0.0 / 1.0) ", "rgb(0 51 0 / 1.0)",
            "RGB(0 51 0 / 100%)", "rgb(  0  51  0/1  )", "rgb(0 5.1e+1 0 / .1e1)",
        )) {
            assertSame("rgb(0 51 0)", equivalent)
        }

        assertRgb(doubleArrayOf(0.0, 0.5 / 255, 1 / 255.0, 1.0), parse("rgb(0,0.5,1)"))
        assertSame("rgb(0,0.5,1)", "rgb(0 0.5 1)")
        assertRgb(doubleArrayOf(0.0, 0.0, 0.0, 1e-5), parse("rgb(0,0,0,.1e-4)"))
        assertRgb(doubleArrayOf(0.4, 0.2, 0.6, 1.0), parse("rgb(102,51,153)"))
        assertRgb(doubleArrayOf(26 / 255.0, 207 / 255.0, 26 / 255.0, 0.5), parse("rgb(26,207,26,0.5)"))
        assertRgb(doubleArrayOf(26 / 255.0, 207 / 255.0, 26 / 255.0, 0.73), parse("rgba(26,207,26,.73)"))
        assertRgb(doubleArrayOf(0.5, 0.0, 0.0, 1.0), parse("rgb(127.5 0 0)"))
        assertRgb(doubleArrayOf(128 / 255.0, 0.0, 0.0, 1.0), parse("rgb(128 0 0)"))
        assertRgb(doubleArrayOf(100 / 255.0, 200 / 255.0, 1.0, 1.0), parse("rgb(100 200 300)"))
        assertRgb(doubleArrayOf(0.0, 1.0, 0.6, 1.0), parse("rgb(-0 255 153)"))
        assertRgb(doubleArrayOf(0.0, 1.0, 0.6, 1.0), parse("rgb(-100 300 153)"))
        assertRgb(doubleArrayOf(0.0, 1.0, 0.0, 1.0), parse("rgb(-51, 306, 0)"))
        assertRgb(doubleArrayOf(0.0, 0.0, 0.0, 0.1), parse("rgba(0,0,0,0.1)"))
        assertSame("rgba(0,0,0,0.1)", "rgb(0 0 0 / .1)")
        assertSame("rgba(0,0,0,0.1)", "rgb(0 0 0 / 10%)")

        // alpha clamping
        for (input in listOf(
            "rgb(0 0 0 / .0)", "rgb(0 0 0 / -.0)", "rgb(0 0 0 / -3.4e-2)",
            "rgb(0 0 0 / -.2)", "rgb(0 0 0 / -10%)",
        )) {
            assertRgb(doubleArrayOf(0.0, 0.0, 0.0, 0.0), parse(input))
        }
        for (input in listOf("rgb(0 0 0 / 1.0)", "rgb(0 0 0 / 1.1)", "rgb(0 0 0 / 110%)")) {
            assertRgb(doubleArrayOf(0.0, 0.0, 0.0, 1.0), parse(input))
        }

        // rgb 0%..100%
        assertRgb(doubleArrayOf(0.0, 0.5, 0.0, 1.0), parse("rgb(0% 50% 0%)"))
        for (equivalent in listOf(
            "rgb(0%, 50%, 0%)", "rgba(0%, 50%, 0%, 1)", "rgba(0%, 50%, 0%, 100%)",
            "rgba(-0e1%,5E1%,0%,1)", "rgb(0% 50% 0% / 1.0)", "rgb(.0% 50% 0% / 100%)",
            "rgb(0.0% 50.0% 0.0% / 1)", "rgb( 0% 50% 0% /  100% )", "rgb(-1e-9% 50% 0% /1)",
        )) {
            assertSame("rgb(0% 50% 0%)", equivalent)
        }

        assertRgb(doubleArrayOf(0.0, 1.0, 0.6, 1.0), parse("rgb(-0% 100% 60%)"))
        assertRgb(doubleArrayOf(0.0, 1.0, 0.6, 1.0), parse("rgb(-10% 200% 60%)"))
        assertRgb(doubleArrayOf(1.0, 1.0, 1.0, 1.0), parse("rgb(100%,200%,300%)"))
        assertRgb(doubleArrayOf(1.0, 0.51, 1.0, 1.0), parse("rgb(128% 51% 255%)"))
        assertRgb(doubleArrayOf(0.0, 0.0, 0.0, 0.1), parse("rgba(0%,0%,0%,0.1)"))
        assertSame("rgba(0%,0%,0%,0.1)", "rgb(0% 0% 0% / .1)")
        assertSame("rgba(0%,0%,0%,0.1)", "rgb(0% 0% 0% / 10%)")

        for (input in listOf(
            "rgb(0% 0% 0% / .0)", "rgb(0% 0% 0% / -.0)", "rgb(0% 0% 0% / -3.4e-2)",
            "rgb(0% 0% 0% / -.2)", "rgb(0% 0% 0% / -10%)",
        )) {
            assertRgb(doubleArrayOf(0.0, 0.0, 0.0, 0.0), parse(input))
        }
        for (input in listOf("rgb(0% 0% 0% / 1.0)", "rgb(0% 0% 0% / 1.1)", "rgb(0% 0% 0% / 110%)")) {
            assertRgb(doubleArrayOf(0.0, 0.0, 0.0, 1.0), parse(input))
        }
    }

    @Test
    fun `should return null when provided with invalid rgb value`() {
        assertNull(parse("rgb (0,0,0)"))
        assertNull(parse("rgba (0,0,0,0)"))

        val invalidArgs = listOf(
            // values must be all numbers or all percentages
            "10%, 50%, 0", "255, 50%, 0%", "10%, 50%, 0, 1", "255, 50%, 0%, 1",
            "0 50% 255 / 1", "0 50% 0 / 1", "128 51% 255",
            // comma-optional syntax requires no commas at all
            "0, 0 0", "0, 0, 0 0", "0, 0, 0 / 1", "0 0 0, 1",
            // angles and keywords are not accepted in the rgb function
            "0, 0, 0deg", "0, 0, 0, 0deg", "0, 0, light", "0, 0, 0, light",
            // invalid numbers
            "--1,0,0", "+-1,0,0", "++1,0,0", "1.1.1,0,0", ".-1,0,0", "..1,0,0",
            "1e1.1,0,0", "1e.1,0,0", "--1e1,0,0", "+-1e1,0,0",
            // the rgb function requires 3 or 4 arguments
            "", "0", ", 0,", "0, 0", "0, 0,", ", 0, 0", "0 0 0 /",
            "0, 0, 0, 0, 0", "0, 0, 0, 0, 0,", ", 0, 0, 0, 0, 0",
            "0%", ", 0%,", "0%, 0%", "0%, 0%,", ", 0%, 0%", "0%, 0%, 0%,", "0% 0% 0% /",
            "0%, 0%, 0%, 0%, 0%", "0%, 50%, 100%,", ", 0%, 50%, 100%",
            ", 0%, 50%, 100%, 100%", "0%, 50%, 100%, 100%,",
        )

        for (args in invalidArgs) {
            for (fn in listOf("rgb", "rgba")) {
                assertNull(parse("$fn($args)"), "'$fn($args)' should not parse")
            }
        }
    }

    // endregion

    // region hsl() and hsla()

    @Test
    fun `should parse valid hsl values`() {
        // Upstream mocks hslToRgb to inspect the parsed triple; here the same values are checked by
        // converting the expected triple with the real function.
        assertRgb(hslToRgb(doubleArrayOf(300.0, 100.0, 25.1, 1.0)), parse("hsl(300,100%,25.1%)"))
        for (equivalent in listOf(
            "hsla(300,100%,25.1%,1)", "hsla(300,100%,25.1%,100%)", "hsl(300 100% 25.1%)",
            "hsl(300 100% 25.1%/1.0)", "hsl(300.0 100% 25.1% / 100%)", "hsl(300deg 100% 25.1% / 100%)",
        )) {
            assertSame("hsl(300,100%,25.1%)", equivalent)
        }

        assertRgb(hslToRgb(doubleArrayOf(240.0, 0.0, 55.0, 0.2)), parse("hsl(240,0%,55%,0.2)"))
        for (equivalent in listOf(
            "hsla(240.0,0%,55%,0.2)", "hsla( 240 ,.0% ,55.0% ,20% )", "hsl(240 0% 55% / 0.2)",
            "hsl(240 0% 55% / 20%)", "hsl(24e1deg 0e1% 55% / 2e-1)", "hsla(240 -1e-7% 55% / 2e1%)",
        )) {
            assertSame("hsl(240,0%,55%,0.2)", equivalent)
        }

        assertRgb(hslToRgb(doubleArrayOf(240.0, 0.0, 55.0, 0.9)), parse("hsl(240,0%,55%,0.9)"))
        assertRgb(hslToRgb(doubleArrayOf(240.0, 0.0, 55.0, 0.0)), parse("hsl(240,0%,55%,.0)"))
        // Hue passes through unclamped; saturation and lightness are clamped to 0..100.
        assertRgb(hslToRgb(doubleArrayOf(700.0, 0.0, 67.3, 1.0)), parse("hsl(700 0% 67.3% / 100%)"))
        assertRgb(hslToRgb(doubleArrayOf(-100.0, 0.0, 67.3, 1.0)), parse("Hsl( -100 -10.5% 67.3% / 100% )"))
    }

    @Test
    fun `should parse valid hsl values and convert to rgb`() {
        assertRgb(doubleArrayOf(1.0, 0.0, 0.0, 1.0), parse("hsl(0 100% 50%)"))
        assertRgb(doubleArrayOf(0.0, 0.0, 1.0, 1.0), parse("hsl(240 100% 50%)"))
        assertRgb(doubleArrayOf(0.0, 0.0, 0.5, 1.0), parse("hsl(240 100% 25%)"))
        assertRgb(doubleArrayOf(0.63, 0.3, 0.9, 1.0), parse("hsl(273 75% 60%)"), tolerance = 0.01)

        assertRgb(doubleArrayOf(0.0, 0.0, 0.0, 1.0), parse("hsl(0 0% 0%)"))
        assertRgb(doubleArrayOf(0.0, 0.0, 0.0, 0.0), parse("hsl(0 0% 0% / 0)"))
        for (equivalent in listOf(
            "hsl(0,0%,0%,+0)", "hsla(0deg,0%,0%,-0)", "hsla(0,0%,0%,0%)",
            " hsla(.0,.0%,.0%,.0%)", "hsla(  0 ,0% ,0% ,.0 ) ",
        )) {
            assertSame("hsl(0 0% 0% / 0)", equivalent)
        }

        assertRgb(doubleArrayOf(0.0, 0.5, 0.0, 1.0), parse("hsl(120 100% 25%)"))
        for (equivalent in listOf(
            "hsl(120.0 100.0% 25.0%)", "hsl(120deg 100% 25%)", "hsl(120 100% 25% / 1.0)",
            "hsl(120deg 100% 25% / 1)", "hsl(120 100% 25% / 100%)", "hsl(120,100%,25%,100%)",
            "hsla(120deg,100%,25%,100%)",
        )) {
            assertSame("hsl(120 100% 25%)", equivalent)
        }

        assertRgb(doubleArrayOf(0.0, 1.0, 0.0, 0.25), parse("hsl(120 100% 50% / .25)"))
        for (equivalent in listOf(
            "HSLA(120,100%,50%,.25)", "hsla(120,100%,50%,25%)", "hsl(120 100% 50%/.25)",
            "hsl(120deg 100% 50% / 25%)", "hsl(480 100% 50% / 25%)", "hsl(-240deg 100% 50% / 25%)",
        )) {
            assertSame("hsl(120 100% 50% / .25)", equivalent)
        }

        assertSame("hsl(0.0 200% 50%)", "hsl(0 100% 50%)")
        assertSame("hsl(-0 -100% -100%)", "hsl(0 0% 0%)")

        for (input in listOf(
            "hsl(0 0% 0% / .0)", "hsl(0 0% 0% / -.0)", "hsl(0 0% 0% / -3.4e-2)",
            "hsl(0 0% 0% / -.2)", "hsl(0 0% 0% / -10%)",
        )) {
            assertRgb(doubleArrayOf(0.0, 0.0, 0.0, 0.0), parse(input))
        }
        for (input in listOf("hsl(0 0% 0% / 1.0)", "hsl(0 0% 0% / 1.1)", "hsl(0 0% 0% / 110%)")) {
            assertRgb(doubleArrayOf(0.0, 0.0, 0.0, 1.0), parse(input))
        }
    }

    @Test
    fun `should return null when provided with invalid hsl value`() {
        assertNull(parse("hsl (0,0%,0%)"))
        assertNull(parse("hsla (0,0%,0%,1)"))

        val invalidArgs = listOf(
            "0,0%,0 %",
            // the first parameter of hsl/hsla must be a number or angle
            "0%,0%,0%", "0 deg,0%,0%",
            // the second and third parameters must be percentages
            "10, 50%, 0",
            // comma-optional syntax requires no commas at all
            "0, 0% 0%", "0, 0% 0%, 1",
            // keywords are not accepted
            "0,0%,light,1",
            // invalid numbers
            "--1,0%,0%", "+-1,0%,0%", "++1,0%,0%", "1.1.1,0%,0%", ".-1,0%,0%", "..1,0%,0%",
            "1e1.1,0%,0%", "1e.1,0%,0%", "--1e1,0%,0%", "+-1e1,0%,0%",
            // the hsl function requires 3 or 4 arguments
            "", "0", "0 0%", "0, 0%,", ", 0%, 0%", "0,0%,0%,1,0%", "0,0,0", "0,0%,0",
            "0,0,0%", "0 0% 0% /", "0,0%,0%,", ", 0%,0%,0%",
        )

        for (args in invalidArgs) {
            for (fn in listOf("hsl", "hsla")) {
                assertNull(parse("$fn($args)"), "'$fn($args)' should not parse")
            }
        }
    }

    // endregion
}
