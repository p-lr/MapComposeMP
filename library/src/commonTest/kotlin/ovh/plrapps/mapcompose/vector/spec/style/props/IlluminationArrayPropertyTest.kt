package ovh.plrapps.mapcompose.vector.spec.style.props

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import ovh.plrapps.mapcompose.vector.spec.style.MapLibreStyle
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.ColorArray
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.NumberArray
import ovh.plrapps.mapcompose.vector.spec.style.hillshade.HillshadePaint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `hillshade`'s illumination properties are `numberArray` and `colorArray`, and both spellings the
 * spec allows have to decode.
 *
 * They were modelled as a scalar and a colour until multidirectional hillshade was ported, so an
 * array-valued `hillshade-illumination-direction` threw out of the value decoder -- and since
 * `getMapLibreConfiguration` turns any decode failure into `Result.failure`, that one property took
 * the whole style down. These tests pin both the parsing and the typing.
 */
class IlluminationArrayPropertyTest {

    private val bare = Json { ignoreUnknownKeys = true }

    private fun paint(json: String): HillshadePaint =
        bare.decodeFromString(HillshadePaint.serializer(), json)

    @Test
    fun aBareNumberIsAOneElementArray() {
        val direction = paint("""{"hillshade-illumination-direction":335}""")
            .hillshadeIlluminationDirection

        assertEquals(listOf(335.0), direction.processAsNumberArray())
    }

    @Test
    fun anArrayOfNumbersKeepsEveryLight() {
        val direction = paint("""{"hillshade-illumination-direction":[335,180,90]}""")
            .hillshadeIlluminationDirection

        assertEquals(listOf(335.0, 180.0, 90.0), direction.processAsNumberArray())
    }

    @Test
    fun aBareColourIsAOneElementArray() {
        val shadow = paint("""{"hillshade-shadow-color":"#ff0000"}""").hillshadeShadowColor

        assertEquals(listOf(Color.Red), shadow.processAsColorArray())
    }

    @Test
    fun anArrayOfColoursKeepsEveryLight() {
        val shadow = paint("""{"hillshade-shadow-color":["#ff0000","#00ff00"]}""")
            .hillshadeShadowColor

        assertEquals(listOf(Color.Red, Color.Green), shadow.processAsColorArray())
    }

    @Test
    fun aZoomExpressionCompilesForAnArrayProperty() {
        val direction = paint(
            """{"hillshade-illumination-direction":["interpolate",["linear"],["zoom"],
               |0,["literal",[0,90]],10,["literal",[180,270]]]}""".trimMargin()
        ).hillshadeIlluminationDirection

        assertTrue(
            direction is ExpressionOrValue.Expression,
            "an interpolate over a numberArray must compile, but was $direction",
        )
        assertEquals(listOf(0.0, 90.0), direction.processAsNumberArray(zoom = 0.0))
        assertEquals(listOf(90.0, 180.0), direction.processAsNumberArray(zoom = 5.0))
        assertEquals(listOf(180.0, 270.0), direction.processAsNumberArray(zoom = 10.0))
    }

    @Test
    fun aScalarZoomExpressionStillCoercesToAnArray() {
        // Every style written before multidirectional hillshade spells this as a plain number.
        val direction = paint(
            """{"hillshade-illumination-direction":["interpolate",["linear"],["zoom"],0,0,10,180]}"""
        ).hillshadeIlluminationDirection

        assertEquals(listOf(90.0), direction.processAsNumberArray(zoom = 5.0))
    }

    @Test
    fun aColourArrayInterpolatesEveryEntry() {
        val shadow = paint(
            """{"hillshade-shadow-color":["interpolate",["linear"],["zoom"],
               |0,["literal",["#000000","#ff0000"]],10,["literal",["#ffffff","#ff0000"]]]}"""
                .trimMargin()
        ).hillshadeShadowColor

        val midway = assertNotNull(shadow.processAsColorArray(zoom = 5.0))
        assertEquals(2, midway.size)
        assertTrue(midway[0].red > 0.4f && midway[0].red < 0.6f, "the first light greys out")
        assertEquals(Color.Red, midway[1], "the second one does not move")
    }

    @Test
    fun anUnparseableColourArrayIsInvalidRatherThanFatal() {
        val shadow = paint("""{"hillshade-shadow-color":["get","not-a-colour"]}""")
            .hillshadeShadowColor

        // The property compiles -- `get` returns `value`, which the parser coerces -- and fails at
        // evaluation time, where `StyleExpression` swallows it and hands back the default.
        assertNull(shadow.processAsColorArray())
    }

    @Test
    fun aStyleWithArrayValuedIlluminationLoads() {
        val style = """
            {
              "version": 8,
              "sources": {"terrain": {"type": "raster-dem", "tiles": ["https://x/{z}/{x}/{y}.png"]}},
              "layers": [{
                "id": "hillshade",
                "type": "hillshade",
                "source": "terrain",
                "paint": {
                  "hillshade-method": "multidirectional",
                  "hillshade-illumination-direction": [335, 180, 90, 0],
                  "hillshade-illumination-altitude": [45, 30],
                  "hillshade-shadow-color": ["#000000", "#111111"],
                  "hillshade-highlight-color": "#ffffff"
                }
              }]
            }
        """.trimIndent()

        val decoded = ovh.plrapps.mapcompose.vector.data.json
            .decodeFromString(MapLibreStyle.serializer(), style)

        assertEquals(1, decoded.layers?.size)
    }

    @Test
    fun numberArrayParseFollowsUpstream() {
        assertEquals(listOf(1.0), assertNotNull(NumberArray.parse(1.0)).values)
        assertEquals(listOf(1.0, 2.0), assertNotNull(NumberArray.parse(listOf(1.0, 2.0))).values)
        assertEquals(emptyList(), assertNotNull(NumberArray.parse(emptyList<Any?>())).values)
        assertNull(NumberArray.parse("335"))
        assertNull(NumberArray.parse(listOf(1.0, "2")))
        assertNull(NumberArray.parse(null))
    }

    @Test
    fun colorArrayParseFollowsUpstream() {
        assertEquals(listOf(Color.Red), assertNotNull(ColorArray.parse("#ff0000")).values)
        assertEquals(
            listOf(Color.Red, Color.Green),
            assertNotNull(ColorArray.parse(listOf("#ff0000", "#00ff00"))).values,
        )
        assertNull(ColorArray.parse("not a colour"))
        assertNull(ColorArray.parse(listOf("#ff0000", 1.0)))
        assertNull(ColorArray.parse(null))
    }

    @Test
    fun colorArrayInterpolationRefusesMismatchedLengths() {
        val one = ColorArray(listOf(Color.Black))
        val two = ColorArray(listOf(Color.Black, Color.White))

        val failed = runCatching { ColorArray.interpolate(one, two, 0.5) }
        assertTrue(failed.isFailure, "upstream throws rather than padding the shorter array")
    }
}
