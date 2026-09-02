package ovh.plrapps.mapcompose.vector.renderer.utils

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineTessellationTest {

    private val outset = lineOutset(gapWidth = 0f, width = 4f, devicePixelRatio = 1f)
    private val blur2 = blur2(blur = 0f, devicePixelRatio = 1f)
    private val ringCount = alphaRings(outset, inset = 0f, blur2 = blur2).size

    private fun tessellate(
        vararg coordinates: Float,
        isPolygon: Boolean = false,
        join: String = "miter",
        cap: String = "butt",
        miterLimit: Float = 2f,
        roundLimit: Float = 1.05f,
        offset: Float = 0f,
        inset: Float = 0f,
        sharpCornerOffset: Float = SHARP_CORNER_OFFSET,
    ): List<LineMesh> = tessellateLine(
        points = coordinates,
        pointCount = coordinates.size / 2,
        isPolygon = isPolygon,
        join = join,
        cap = cap,
        miterLimit = miterLimit,
        roundLimit = roundLimit,
        outset = outset,
        inset = inset,
        blur2 = blur2,
        offset = offset,
        sharpCornerOffset = sharpCornerOffset,
    )

    private fun LineMesh.columnCount(): Int = vertexCount / ringCount

    @Test
    fun `a straight segment is two cross-sections`() {
        val mesh = tessellate(0f, 32f, 64f, 32f).single()

        assertEquals(2, mesh.columnCount())
        // Two triangles per band, and one band is dropped only when it is transparent throughout.
        assertEquals(2 * (ringCount - 1) * 3, mesh.indexCount)
    }

    @Test
    fun `a straight segment spans the ribbon and nothing more`() {
        val mesh = tessellate(0f, 32f, 64f, 32f).single()

        for (i in 0 until mesh.vertexCount) {
            val y = mesh.positions[2 * i + 1]
            assertTrue(abs(y - 32f) <= outset + 1e-4f, "vertex $i is inside the ribbon: y=$y")
        }
        val extremes = (0 until mesh.vertexCount).map { mesh.positions[2 * it + 1] }
        assertTrue(extremes.any { abs(it - (32f - outset)) < 1e-4f }, "the ribbon reaches its top edge")
        assertTrue(extremes.any { abs(it - (32f + outset)) < 1e-4f }, "and its bottom edge")
    }

    @Test
    fun `the ribbon edge is transparent and its centre is opaque`() {
        val mesh = tessellate(0f, 32f, 64f, 32f).single()

        for (i in 0 until mesh.vertexCount) {
            val y = mesh.positions[2 * i + 1]
            val alpha = mesh.alphas[i]
            if (abs(abs(y - 32f) - outset) < 1e-4f) {
                assertEquals(0f, alpha, "the edge fades out at vertex $i")
            }
            if (abs(y - 32f) < 1e-4f) {
                assertEquals(1f, alpha, "the centre is opaque at vertex $i")
            }
        }
    }

    @Test
    fun `a right angle within the miter limit stays a miter`() {
        // cos(45 degrees) gives a miter length of about 1.414, under the default limit of 2.
        val mesh = tessellate(0f, 0f, 32f, 0f, 32f, 32f, sharpCornerOffset = 100f).single()

        assertEquals(3, mesh.columnCount(), "start cap, one miter column, end cap")
    }

    @Test
    fun `a corner past the miter limit becomes a bevel`() {
        val mesh = tessellate(0f, 0f, 32f, 0f, 32f, 32f, miterLimit = 1.1f, sharpCornerOffset = 100f).single()

        assertEquals(4, mesh.columnCount(), "a bevel closes one segment and opens the next")
    }

    @Test
    fun `line-join bevel forces the bevel regardless of the miter limit`() {
        // Upstream pins miterLimit to 1.05 when the join is bevel.
        val mesh = tessellate(0f, 0f, 32f, 0f, 32f, 32f, join = "bevel", miterLimit = 10f, sharpCornerOffset = 100f).single()

        assertEquals(4, mesh.columnCount())
    }

    @Test
    fun `a shallow corner keeps a bevel join as a miter`() {
        // Nearly straight: the miter length is barely above 1 and a bevel would not be visible.
        val mesh = tessellate(0f, 0f, 32f, 0f, 64f, 1f, join = "bevel").single()

        assertEquals(3, mesh.columnCount())
    }

    @Test
    fun `line-round-limit degrades a round join to a miter`() {
        val corner = floatArrayOf(0f, 0f, 32f, 0f, 32f, 32f)

        val rounded = tessellateLine(
            points = corner, pointCount = 3, isPolygon = false,
            join = "round", cap = "butt", miterLimit = 2f, roundLimit = 1.05f,
            outset = outset, inset = 0f, blur2 = blur2, sharpCornerOffset = 100f,
        )
        val miteredAway = tessellateLine(
            points = corner, pointCount = 3, isPolygon = false,
            join = "round", cap = "butt", miterLimit = 2f, roundLimit = 2f,
            outset = outset, inset = 0f, blur2 = blur2, sharpCornerOffset = 100f,
        )

        val roundedColumns = rounded.sumOf { it.vertexCount }
        val miteredColumns = miteredAway.sumOf { it.vertexCount }
        assertTrue(
            miteredColumns < roundedColumns,
            "a round limit above the miter length replaces the fan with one column: $miteredColumns vs $roundedColumns"
        )
        assertEquals(3, miteredAway.single().columnCount())
    }

    @Test
    fun `a round join fans the corner`() {
        val mesh = tessellate(0f, 0f, 32f, 0f, 32f, 32f, join = "round", sharpCornerOffset = 100f).single()

        // The bevel's two columns plus at least one pie slice between them.
        assertTrue(mesh.columnCount() > 4, "a round join adds pie slices: ${mesh.columnCount()}")
    }

    @Test
    fun `a square cap extends the line by its half width`() {
        val mesh = tessellate(16f, 32f, 48f, 32f, cap = "square").single()

        val minX = (0 until mesh.vertexCount).minOf { mesh.positions[2 * it] }
        val maxX = (0 until mesh.vertexCount).maxOf { mesh.positions[2 * it] }
        assertTrue(abs(minX - (16f - outset)) < 1e-4f, "the start cap reaches past the first point: $minX")
        assertTrue(abs(maxX - (48f + outset)) < 1e-4f, "and the end cap past the last: $maxX")
    }

    @Test
    fun `a butt cap stops at the end point`() {
        val mesh = tessellate(16f, 32f, 48f, 32f).single()

        val minX = (0 until mesh.vertexCount).minOf { mesh.positions[2 * it] }
        val maxX = (0 until mesh.vertexCount).maxOf { mesh.positions[2 * it] }
        assertEquals(16f, minX)
        assertEquals(48f, maxX)
    }

    @Test
    fun `a round cap sweeps a half disc past each end`() {
        val meshes = tessellate(16f, 32f, 48f, 32f, cap = "round")

        val minX = meshes.minOf { mesh -> (0 until mesh.vertexCount).minOf { mesh.positions[2 * it] } }
        val maxX = meshes.maxOf { mesh -> (0 until mesh.vertexCount).maxOf { mesh.positions[2 * it] } }
        // A fan is polygonal, so it reaches its radius only to within the chord sag it is built to.
        val sag = 0.25f
        assertTrue(minX <= 16f - outset + sag && minX >= 16f - outset, "the cap reaches back past the start: $minX")
        assertTrue(maxX >= 48f + outset - sag && maxX <= 48f + outset, "and past the end: $maxX")

        // Every cap vertex is within the disc of radius outset around its end point.
        for (mesh in meshes) {
            for (i in 0 until mesh.vertexCount) {
                val x = mesh.positions[2 * i]
                val y = mesh.positions[2 * i + 1]
                val nearest = if (x < 32f) 16f else 48f
                val radius = kotlin.math.hypot(x - nearest, y - 32f)
                assertTrue(
                    x in 16f..48f || radius <= outset + 1e-3f,
                    "cap vertex ($x, $y) stays inside its half disc"
                )
            }
        }
    }

    @Test
    fun `a polygon ring is closed with joins and no caps`() {
        val ring = floatArrayOf(8f, 8f, 56f, 8f, 56f, 56f, 8f, 56f, 8f, 8f)
        val mesh = tessellateLine(
            points = ring, pointCount = 5, isPolygon = true,
            join = "miter", cap = "square", miterLimit = 2f, roundLimit = 1.05f,
            outset = outset, inset = 0f, blur2 = blur2,
        ).single()

        // A square cap would push a vertex outside the ring's own bounding box plus one half width.
        for (i in 0 until mesh.vertexCount) {
            val x = mesh.positions[2 * i]
            val y = mesh.positions[2 * i + 1]
            assertTrue(x >= 8f - outset - 1e-4f && x <= 56f + outset + 1e-4f, "x stays within the ring: $x")
            assertTrue(y >= 8f - outset - 1e-4f && y <= 56f + outset + 1e-4f, "y stays within the ring: $y")
        }
    }

    @Test
    fun `a gapped line leaves its core untessellated`() {
        val gapInset = lineInset(gapWidth = 12f, devicePixelRatio = 1f)
        val gapOutset = lineOutset(gapWidth = 12f, width = 4f, devicePixelRatio = 1f)
        val mesh = tessellateLine(
            points = floatArrayOf(0f, 32f, 64f, 32f), pointCount = 2, isPolygon = false,
            join = "miter", cap = "butt", miterLimit = 2f, roundLimit = 1.05f,
            outset = gapOutset, inset = gapInset, blur2 = blur2,
        ).single()

        val rings = alphaRings(gapOutset, gapInset, blur2)
        val alphas = rings.map { lineAlpha(abs(it) * gapOutset, gapOutset, gapInset, blur2) }
        val drawnBands = (0 until rings.size - 1).count { alphas[it] > 1e-4f || alphas[it + 1] > 1e-4f }
        assertTrue(drawnBands < rings.size - 1, "the core band is transparent throughout")
        assertEquals(drawnBands * 2 * 3, mesh.indexCount)
    }

    @Test
    fun `line-offset shifts the whole cross-section`() {
        val straight = tessellate(0f, 32f, 64f, 32f).single()
        val shifted = tessellate(0f, 32f, 64f, 32f, offset = 4f).single()

        for (i in 0 until straight.vertexCount) {
            assertEquals(straight.positions[2 * i], shifted.positions[2 * i], "x is untouched")
            assertTrue(
                abs((shifted.positions[2 * i + 1] - straight.positions[2 * i + 1]) - 4f) < 1e-4f,
                "y moves by the offset at vertex $i"
            )
        }
    }

    @Test
    fun `line-offset compensates a corner by the miter length`() {
        // A right angle has a miter length of 1 / cos(45 degrees), so the offset corner has to move
        // further than the offset itself -- that factor is what the old averaged-normal offset
        // dropped, and what leaves an offset polyline short at a corner.
        val mesh = tessellate(0f, 0f, 32f, 0f, 32f, 32f, offset = 4f, sharpCornerOffset = 100f).single()

        // The middle column is the join; its centre ring is the offset centre line at the corner.
        val centre = ringCount / 2
        val cornerX = mesh.positions[2 * (ringCount + centre)]
        val cornerY = mesh.positions[2 * (ringCount + centre) + 1]

        val miterLength = 1f / kotlin.math.cos(kotlin.math.PI.toFloat() / 4f)
        // The bisector at this corner points along (-1, 1) normalised.
        val expectedX = 32f + 4f * miterLength * (-1f / kotlin.math.sqrt(2f))
        val expectedY = 0f + 4f * miterLength * (1f / kotlin.math.sqrt(2f))

        assertTrue(abs(cornerX - expectedX) < 1e-3f, "the corner moves out along the miter in x: $cornerX")
        assertTrue(abs(cornerY - expectedY) < 1e-3f, "and in y: $cornerY")
    }

    @Test
    fun `a long line is split into chunks a Vertices index list can hold`() {
        val pointCount = 20_000
        val points = FloatArray(pointCount * 2)
        for (i in 0 until pointCount) {
            points[2 * i] = i.toFloat()
            points[2 * i + 1] = if (i % 2 == 0) 0f else 1f
        }

        val meshes = tessellateLine(
            points = points, pointCount = pointCount, isPolygon = false,
            join = "miter", cap = "butt", miterLimit = 2f, roundLimit = 1.05f,
            outset = outset, inset = 0f, blur2 = blur2,
        )

        assertTrue(meshes.size > 1, "the line needed more than one chunk")
        for (mesh in meshes) {
            assertTrue(mesh.vertexCount <= MAX_VERTEX_ARRAY_LENGTH, "a chunk fits: ${mesh.vertexCount}")
            for (i in 0 until mesh.indexCount) {
                assertTrue(mesh.indices[i] < mesh.vertexCount, "indices stay inside their chunk")
            }
        }
    }

    @Test
    fun `duplicate points are skipped`() {
        val withDuplicates = tessellate(0f, 32f, 32f, 32f, 32f, 32f, 64f, 32f).single()
        val without = tessellate(0f, 32f, 32f, 32f, 64f, 32f).single()

        assertEquals(without.vertexCount, withDuplicates.vertexCount)
    }

    @Test
    fun `a degenerate line draws nothing`() {
        assertTrue(tessellate(10f, 10f, 10f, 10f).isEmpty())
        assertTrue(tessellate(10f, 10f).isEmpty())
    }

    @Test
    fun `progress runs from zero to one along the line`() {
        val meshes = tessellateLine(
            points = floatArrayOf(0f, 32f, 64f, 32f), pointCount = 2, isPolygon = false,
            join = "miter", cap = "butt", miterLimit = 2f, roundLimit = 1.05f,
            outset = outset, inset = 0f, blur2 = blur2, totalLength = 64f,
        )
        val mesh = meshes.single()

        assertEquals(0f, mesh.progress[0])
        assertEquals(1f, mesh.progress[mesh.vertexCount - 1])
    }

    @Test
    fun `a sharp corner gets an extra cross-section on each side`() {
        // A right angle is sharper than upstream's 75 degree threshold, so it pins the ribbon a
        // fixed distance before and after the corner rather than letting the join stretch back
        // along both segments.
        val withInsertion = tessellate(0f, 0f, 32f, 0f, 32f, 32f, sharpCornerOffset = 8f).single()
        val without = tessellate(0f, 0f, 32f, 0f, 32f, 32f, sharpCornerOffset = 100f).single()

        assertEquals(without.columnCount() + 2, withInsertion.columnCount())

        val inserted = (0 until withInsertion.vertexCount).map { withInsertion.positions[2 * it] }
        assertTrue(inserted.any { abs(it - 24f) < 1e-4f }, "one sits eight pixels before the corner")
    }

    @Test
    fun `a shallow corner gets no extra cross-sections`() {
        // Well inside the 75 degree threshold.
        val mesh = tessellate(0f, 0f, 32f, 0f, 64f, 8f, sharpCornerOffset = 8f).single()

        assertEquals(3, mesh.columnCount())
    }
}
