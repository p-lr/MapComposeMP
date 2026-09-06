package ovh.plrapps.mapcompose.vector.ui.symbols

import androidx.compose.ui.geometry.Offset
import ovh.plrapps.mapcompose.vector.renderer.Point
import ovh.plrapps.mapcompose.vector.symbol.LabelPath
import ovh.plrapps.mapcompose.vector.symbol.PathPoint
import ovh.plrapps.mapcompose.vector.symbol.SymbolFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The decisions the draw pass makes about a label that follows a line -- whether to bend it, which
 * way round to read it, and at what angle to draw it when it stays straight.
 *
 * These are the pure halves of [CurvedLabel]; what they produce is drawn by `drawTextAlongPath`,
 * which needs a canvas and so is exercised in `skiaTest`.
 */
class CurvedLabelTest {

    @Test
    fun `a path within half a pixel of its chord counts as straight`() {
        val nearlyStraight = listOf(Offset(0f, 0f), Offset(50f, 0.3f), Offset(100f, 0f))

        assertTrue(CurvedLabel.bendOf(nearlyStraight) < 0.5f)
    }

    @Test
    fun `a bend is measured from the chord and not from the ends`() {
        val bent = listOf(Offset(0f, 0f), Offset(50f, 20f), Offset(100f, 0f))

        assertEquals(20f, CurvedLabel.bendOf(bent), 1e-3f)
        // A two-point path has no interior vertex, so it can never bend.
        assertEquals(0f, CurvedLabel.bendOf(listOf(Offset(0f, 0f), Offset(100f, 100f))))
    }

    @Test
    fun `a straight label takes the chord's angle and not the anchor segment's`() {
        // The anchor segment runs flat while the label as a whole climbs: drawing at the segment's
        // angle would make the label jump the moment the path bends past the threshold.
        val path = LabelPath(
            points = listOf(Offset(0f, 0f), Offset(50f, 0f), Offset(100f, 100f)),
            anchorIndex = 1,
        )

        assertEquals(45f, CurvedLabel.chordAngleDeg(path), 1e-3f)
    }

    @Test
    fun `a label running right to left on screen is turned around`() {
        val backwards = listOf(PathPoint(100f, 0f, 180f), PathPoint(0f, 0f, 180f))
        val forwards = listOf(PathPoint(0f, 0f, 0f), PathPoint(100f, 0f, 0f))

        assertTrue(CurvedLabel.readsBackwards(backwards, mapRotationDeg = 0f))
        assertFalse(CurvedLabel.readsBackwards(forwards, mapRotationDeg = 0f))
    }

    @Test
    fun `the map's bearing decides which way a label reads`() {
        // Map-aligned and running left to right, but the map is turned half way round, so on screen
        // it runs the other way -- which layout cannot know and is why the decision is made here.
        val forwards = listOf(PathPoint(0f, 0f, 0f), PathPoint(100f, 0f, 0f))

        assertTrue(CurvedLabel.readsBackwards(forwards, mapRotationDeg = 180f))
        assertFalse(CurvedLabel.readsBackwards(forwards, mapRotationDeg = 30f))
    }

    @Test
    fun `the label's path is the stretch the layout pass cut`() {
        // Layout cuts the label's own few vertices out of the road; the draw pass only projects
        // them, so a road with hundreds of vertices costs the same as one with three.
        val symbol = SymbolFixtures.textInstance(
            key = "road",
            width = 40f,
            globalLine = listOf(Point(0.4, 0.5), Point(0.5, 0.5), Point(0.6, 0.5)),
            globalAnchorIndex = 1,
        )

        val path = CurvedLabel.pathFor(symbol) { point ->
            Offset((point.x * 1000f).toFloat(), (point.y * 1000f).toFloat())
        }

        assertNotNull(path)
        assertEquals(listOf(400f, 500f, 600f), path.points.map { it.x })
        assertEquals(1, path.anchorIndex)
    }

    @Test
    fun `a symbol with no line has no path`() {
        val symbol = SymbolFixtures.textInstance(key = "poi")

        assertEquals(null, CurvedLabel.pathFor(symbol) { Offset.Zero })
    }
}
