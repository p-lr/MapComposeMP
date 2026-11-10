package ovh.plrapps.mapcompose.vector.ui.symbols

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import ovh.plrapps.mapcompose.vector.data.glyphs.RenderedGlyph
import ovh.plrapps.mapcompose.vector.renderer.Point
import ovh.plrapps.mapcompose.vector.symbol.PathInterpolator
import ovh.plrapps.mapcompose.vector.symbol.SymbolProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [LabelPathScratch] is what the draw pass walks a line label with, and it is
 * `SymbolProjection.placeGlyphsAlongPath` rewritten over reusable arrays. These hold the two to the
 * same positions, so the port of `placeGlyphsAlongLine` that `SymbolProjectionTest` covers is still
 * what is drawn.
 */
class LabelPathScratchTest {

    private val strip = ImageBitmap(1, 1)

    private fun quads(vararg alongOffsets: Float): List<RenderedGlyph> = alongOffsets.map {
        RenderedGlyph(
            bitmap = strip, alongOffset = it, left = 0f, top = 0f, width = 1f, height = 1f,
            srcX = 0, srcY = 0,
        )
    }

    /** A world of 1000 px with the origin at its corner, so a point is its coordinate times 1000. */
    private fun scratchOf(points: List<Offset>, anchorIndex: Int) = LabelPathScratch().also {
        assertTrue(
            it.load(points.map { p -> Point(p.x / 1000.0, p.y / 1000.0) }, anchorIndex, 1000.0, 1000.0, 0.0, 0.0)
        )
    }

    private fun assertSamePositions(
        points: List<Offset>,
        anchorIndex: Int,
        offsets: FloatArray,
        perpendicular: Float = 0f,
        flip: Boolean = false,
        scratch: LabelPathScratch,
    ) {
        val anchorDistance = PathInterpolator(points.map { it.x to it.y }).distanceTo(anchorIndex)
        val expected = assertNotNull(
            SymbolProjection.placeGlyphsAlongPath(points, anchorDistance, offsets, perpendicular, flip)
        )
        assertEquals(expected.size, scratch.glyphCount)
        for (i in expected.indices) {
            assertEquals(expected[i].x, scratch.glyphX(i), 1e-3f, "x of glyph $i")
            assertEquals(expected[i].y, scratch.glyphY(i), 1e-3f, "y of glyph $i")
            assertEquals(expected[i].angleDeg, scratch.glyphAngleDeg(i), 1e-3f, "angle of glyph $i")
        }
    }

    private val corner = listOf(Offset(20f, 80f), Offset(80f, 80f), Offset(80f, 140f))

    @Test
    fun `glyphs around a corner land where the reference walk puts them`() {
        val offsets = floatArrayOf(-40f, -20f, 0f, 20f, 40f)
        val scratch = scratchOf(corner, anchorIndex = 1)

        val placed = scratch.placeGlyphs(
            quads(*offsets), textScale = 1f, alongOffset = 0f, perpendicular = 0f,
            keepUpright = true, curveEpsilonPx = 0.5f, mapRotationDeg = 0f,
        )

        assertTrue(placed)
        assertSamePositions(corner, 1, offsets, scratch = scratch)
    }

    @Test
    fun `the perpendicular offset moves each glyph along its own segment's normal`() {
        val offsets = floatArrayOf(-30f, 0f, 30f)
        val scratch = scratchOf(corner, anchorIndex = 1)

        assertTrue(
            scratch.placeGlyphs(
                quads(*offsets), textScale = 1f, alongOffset = 0f, perpendicular = 6f,
                keepUpright = true, curveEpsilonPx = 0.5f, mapRotationDeg = 0f,
            )
        )

        assertSamePositions(corner, 1, offsets, perpendicular = 6f, scratch = scratch)
    }

    @Test
    fun `a label that would read backwards is turned around`() {
        val reversed = corner.reversed()
        val offsets = floatArrayOf(-30f, 0f, 30f)
        val scratch = scratchOf(reversed, anchorIndex = 1)

        assertTrue(
            scratch.placeGlyphs(
                quads(*offsets), textScale = 1f, alongOffset = 0f, perpendicular = 0f,
                keepUpright = true, curveEpsilonPx = 0.5f, mapRotationDeg = 0f,
            )
        )

        assertSamePositions(reversed, 1, offsets, flip = true, scratch = scratch)
    }

    @Test
    fun `without keep-upright the label keeps the line's own direction`() {
        val reversed = corner.reversed()
        val offsets = floatArrayOf(-30f, 0f, 30f)
        val scratch = scratchOf(reversed, anchorIndex = 1)

        assertTrue(
            scratch.placeGlyphs(
                quads(*offsets), textScale = 1f, alongOffset = 0f, perpendicular = 0f,
                keepUpright = false, curveEpsilonPx = 0.5f, mapRotationDeg = 0f,
            )
        )

        assertSamePositions(reversed, 1, offsets, flip = false, scratch = scratch)
    }

    @Test
    fun `the text scale stretches the glyph offsets`() {
        val scratch = scratchOf(corner, anchorIndex = 1)

        assertTrue(
            scratch.placeGlyphs(
                quads(-20f, 0f, 20f), textScale = 2f, alongOffset = 0f, perpendicular = 0f,
                keepUpright = true, curveEpsilonPx = 0.5f, mapRotationDeg = 0f,
            )
        )

        assertSamePositions(corner, 1, floatArrayOf(-40f, 0f, 40f), scratch = scratch)
    }

    @Test
    fun `a path that hardly bends is left to the straight draw`() {
        val straight = listOf(Offset(0f, 0f), Offset(50f, 0.1f), Offset(100f, 0f))
        val scratch = scratchOf(straight, anchorIndex = 1)

        assertFalse(
            scratch.placeGlyphs(
                quads(-10f, 0f, 10f), textScale = 1f, alongOffset = 0f, perpendicular = 0f,
                keepUpright = true, curveEpsilonPx = 0.5f, mapRotationDeg = 0f,
            )
        )
        assertEquals(0, scratch.glyphCount)
    }

    @Test
    fun `a glyph that runs off the path leaves the label to the straight draw`() {
        val scratch = scratchOf(corner, anchorIndex = 1)

        assertFalse(
            scratch.placeGlyphs(
                quads(-100f, 0f, 100f), textScale = 1f, alongOffset = 0f, perpendicular = 0f,
                keepUpright = true, curveEpsilonPx = 0.5f, mapRotationDeg = 0f,
            )
        )
        assertEquals(0, scratch.glyphCount)
    }
}
