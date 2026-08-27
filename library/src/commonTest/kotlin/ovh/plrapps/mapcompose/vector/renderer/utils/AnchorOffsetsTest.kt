package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import ovh.plrapps.mapcompose.vector.spec.style.symbol.TextAnchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Anchor geometry, against `getAnchorAlignment` and `evaluateVariableOffset` in
 * maplibre-gl-js `src/symbol/symbol_layout.ts`.
 *
 * The signs are the point of the file: an anchor names the side of the box that lands *on* the
 * point, so `top` hangs the label below it. They used to be inverted, mirroring every non-centre
 * label through its own anchor.
 */
class AnchorOffsetsTest {

    private val width = 100f
    private val height = 20f

    private fun center(anchor: TextAnchor) = anchorCenterOffset(anchor, width, height)

    @Test
    fun `center leaves the box on the point`() {
        assertEquals(Offset.Zero, center(TextAnchor.Center))
    }

    @Test
    fun `top puts the box below the point`() {
        // The box's top edge is at the point, so its centre is half a box lower.
        assertEquals(Offset(0f, height / 2f), center(TextAnchor.Top))
    }

    @Test
    fun `bottom puts the box above the point`() {
        assertEquals(Offset(0f, -height / 2f), center(TextAnchor.Bottom))
    }

    @Test
    fun `left puts the box to the right of the point`() {
        assertEquals(Offset(width / 2f, 0f), center(TextAnchor.Left))
    }

    @Test
    fun `right puts the box to the left of the point`() {
        assertEquals(Offset(-width / 2f, 0f), center(TextAnchor.Right))
    }

    @Test
    fun `the corner anchors combine both axes`() {
        assertEquals(Offset(width / 2f, height / 2f), center(TextAnchor.TopLeft))
        assertEquals(Offset(-width / 2f, height / 2f), center(TextAnchor.TopRight))
        assertEquals(Offset(width / 2f, -height / 2f), center(TextAnchor.BottomLeft))
        assertEquals(Offset(-width / 2f, -height / 2f), center(TextAnchor.BottomRight))
    }

    @Test
    fun `alignment is upstream's 0 to 1 pair`() {
        assertEquals(Offset(0.5f, 0.5f), anchorAlignment(TextAnchor.Center))
        assertEquals(Offset(0f, 0.5f), anchorAlignment(TextAnchor.Left))
        assertEquals(Offset(1f, 0f), anchorAlignment(TextAnchor.TopRight))
        assertEquals(Offset(0.5f, 1f), anchorAlignment(TextAnchor.Bottom))
    }

    @Test
    fun `a radial offset pushes the label away from the point`() {
        // `top` anchors the box's top edge at the point, so the label is below it and a radial
        // offset must push it further down.
        assertEquals(Offset(0f, 2f), radialOffsetEms(TextAnchor.Top, 2f))
        assertEquals(Offset(0f, -2f), radialOffsetEms(TextAnchor.Bottom, 2f))
        assertEquals(Offset(2f, 0f), radialOffsetEms(TextAnchor.Left, 2f))
        assertEquals(Offset(-2f, 0f), radialOffsetEms(TextAnchor.Right, 2f))
    }

    @Test
    fun `a diagonal radial offset is split over both axes`() {
        val offset = radialOffsetEms(TextAnchor.TopLeft, 2f)
        // Same distance from the point as a straight anchor, hence the 1/sqrt(2) split.
        val distance = kotlin.math.sqrt(offset.x * offset.x + offset.y * offset.y)
        assertTrue(kotlin.math.abs(distance - 2f) < 1e-4f, "expected 2 but was $distance")
        assertTrue(offset.x > 0f && offset.y > 0f)
    }

    @Test
    fun `a negative radial offset is clamped to zero`() {
        assertEquals(Offset.Zero, radialOffsetEms(TextAnchor.Top, -3f))
    }

    @Test
    fun `center has no radial direction`() {
        assertEquals(Offset.Zero, radialOffsetEms(TextAnchor.Center, 5f))
    }

    @Test
    fun `variable anchor offsets are read pairwise`() {
        val raw = Json.parseToJsonElement("""["top",[0,1],"left",[2,-3]]""") as JsonArray
        val offsets = variableAnchorOffsets(raw.toList())
        assertEquals(Offset(0f, 1f), offsets[TextAnchor.Top])
        assertEquals(Offset(2f, -3f), offsets[TextAnchor.Left])
        assertEquals(2, offsets.size)
    }

    @Test
    fun `plain kotlin values are accepted too`() {
        // An expression evaluates to the engine's own values rather than to parsed JSON.
        val offsets = variableAnchorOffsets(listOf("bottom", listOf(1.0, 2.0)))
        assertEquals(Offset(1f, 2f), offsets[TextAnchor.Bottom])
    }

    @Test
    fun `a malformed entry is skipped rather than failing the property`() {
        val offsets = variableAnchorOffsets(listOf("top", "nonsense", "left", listOf(1.0, 1.0)))
        assertEquals(setOf(TextAnchor.Left), offsets.keys)
        assertEquals(emptyMap(), variableAnchorOffsets(null))
        assertEquals(emptyMap(), variableAnchorOffsets(emptyList()))
    }
}
