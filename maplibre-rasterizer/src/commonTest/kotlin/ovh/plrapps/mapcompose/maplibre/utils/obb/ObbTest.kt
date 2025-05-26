package ovh.plrapps.mapcompose.maplibre.utils.obb

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.measureTime

class ObbTest {
    @Test
    fun `test basic OBB creation`() {
        val obb = OBB(
            center = ObbPoint(10f, 10f),
            size = Size(20f, 10f),
            rotation = 0f
        )

        val corners = obb.getCorners()
        assertEquals(4, corners.size)

        // For 0 rotation, corners should be:
        // (-10, -5), (10, -5), (10, 5), (-10, 5)
        val expectedCorners = listOf(
            ObbPoint(0f, 5f),    // (-10 + 10, -5 + 10)
            ObbPoint(20f, 5f),   // (10 + 10, -5 + 10)
            ObbPoint(20f, 15f),  // (10 + 10, 5 + 10)
            ObbPoint(0f, 15f)    // (-10 + 10, 5 + 10)
        )

        corners.forEachIndexed { index, corner ->
            assertTrue(
                abs(corner.x - expectedCorners[index].x) < 0.001f,
                "X coordinate mismatch at corner $index"
            )
            assertTrue(
                abs(corner.y - expectedCorners[index].y) < 0.001f,
                "Y coordinate mismatch at corner $index"
            )
        }
    }

    @Test
    fun `test rotated OBB`() {
        val obb = OBB(
            center = ObbPoint(0f, 0f),
            size = Size(20f, 10f),
            rotation = 90f
        )

        val corners = obb.getCorners()

        // For 90-degree rotation, corners should be:
        // (5, -10), (5, 10), (-5, 10), (-5, -10)
        val expectedCorners = listOf(
            ObbPoint(5f, -10f),
            ObbPoint(5f, 10f),
            ObbPoint(-5f, 10f),
            ObbPoint(-5f, -10f)
        )

        corners.forEachIndexed { index, corner ->
            assertTrue(
                abs(corner.x - expectedCorners[index].x) < 0.001f,
                "X coordinate mismatch at corner $index"
            )
            assertTrue(
                abs(corner.y - expectedCorners[index].y) < 0.001f,
                "Y coordinate mismatch at corner $index"
            )
        }
    }

    @Test
    fun `test OBB intersection`() {
        // Two OBBs that clearly intersect
        val obb1 = OBB(
            center = ObbPoint(0f, 0f),
            size = Size(20f, 10f),
            rotation = 0f
        )

        val obb2 = OBB(
            center = ObbPoint(5f, 5f),
            size = Size(20f, 10f),
            rotation = 45f
        )

        assertTrue(obb1.intersects(obb2))
        assertTrue(obb2.intersects(obb1))

        // Two OBBs that clearly don't intersect
        val obb3 = OBB(
            center = ObbPoint(0f, 0f),
            size = Size(20f, 10f),
            rotation = 0f
        )

        val obb4 = OBB(
            center = ObbPoint(30f, 30f),
            size = Size(20f, 10f),
            rotation = 0f
        )

        assertFalse(obb3.intersects(obb4))
        assertFalse(obb4.intersects(obb3))
    }

    @Test
    fun `test OBB AABB conversion`() {
        val obb = OBB(
            center = ObbPoint(10f, 10f),
            size = Size(20f, 10f),
            rotation = 45f
        )

        val aabb = obb.getAABB()

        // AABB should contain all corners of the OBB
        val corners = obb.getCorners()
        corners.forEach { corner ->
            assertTrue(corner.x >= aabb.minX && corner.x <= aabb.maxX)
            assertTrue(corner.y >= aabb.minY && corner.y <= aabb.maxY)
        }
    }

    @Test
    fun `test edge cases`() {
        // Test with zero rotation
        val obb1 = OBB(
            center = ObbPoint(0f, 0f),
            size = Size(10f, 10f),
            rotation = 0f
        )

        // Test with 180 degree rotation
        val obb2 = OBB(
            center = ObbPoint(0f, 0f),
            size = Size(10f, 10f),
            rotation = 180f
        )

        // Test with negative rotation
        val obb3 = OBB(
            center = ObbPoint(0f, 0f),
            size = Size(10f, 10f),
            rotation = -45f
        )

        // Test with large rotation
        val obb4 = OBB(
            center = ObbPoint(0f, 0f),
            size = Size(10f, 10f),
            rotation = 720f // Two full rotations
        )

        // All these OBBs should be equivalent
        assertTrue(obb1.intersects(obb2))
        assertTrue(obb2.intersects(obb3))
        assertTrue(obb3.intersects(obb4))
    }

    @Test
    fun `test performance`() {
        val numTests = 1000
        val obbs = List(numTests) { i ->
            OBB(
                center = ObbPoint(i * 10f, i * 10f),
                size = Size(20f, 10f),
                rotation = i * 10f
            )
        }

        var intersections = 0
        val duration = measureTime {
            // Test all pairs of OBBs
            for (i in 0 until numTests) {
                for (j in i + 1 until numTests) {
                    if (obbs[i].intersects(obbs[j])) {
                        intersections++
                    }
                }
            }
        }

        println("Performed $numTests OBB intersection tests in ${duration.inWholeMilliseconds}ms")
        println("Found $intersections intersections")

        // Verify that the test didn't take too long
        assertTrue(duration.inWholeMilliseconds < 1000, "Performance test took too long: ${duration.inWholeMilliseconds}ms")
    }

    @Test
    fun `test floating point precision`() {
        val obb1 = OBB(
            center = ObbPoint(0f, 0f),
            size = Size(10f, 10f),
            rotation = 45f
        )

        val obb2 = OBB(
            center = ObbPoint(0.0001f, 0.0001f),
            size = Size(10f, 10f),
            rotation = 45f
        )

        assertTrue(obb1.intersects(obb2), "OBBs should intersect with small offset")

        // Test with very small size difference
        val obb3 = OBB(
            center = ObbPoint(0f, 0f),
            size = Size(10.0001f, 10.0001f),
            rotation = 45f
        )

        assertTrue(obb1.intersects(obb3), "OBBs should intersect with small size difference")
    }

    @Test
    fun `test nested OBBs`() {
        val outer = OBB(
            center = ObbPoint(0f, 0f),
            size = Size(20f, 20f),
            rotation = 0f
        )

        val inner = OBB(
            center = ObbPoint(0f, 0f),
            size = Size(10f, 10f),
            rotation = 45f
        )

        assertTrue(outer.intersects(inner), "Outer OBB should intersect with inner OBB")

        // Test with inner OBB at different positions
        val inner2 = OBB(
            center = ObbPoint(5f, 5f),
            size = Size(10f, 10f),
            rotation = 45f
        )

        assertTrue(outer.intersects(inner2), "Outer OBB should intersect with offset inner OBB")

        // Test with inner OBB touching the edge
        val inner3 = OBB(
            center = ObbPoint(10f, 0f),
            size = Size(10f, 10f),
            rotation = 45f
        )

        assertTrue(outer.intersects(inner3), "Outer OBB should intersect with edge-touching inner OBB")
    }

    @Test
    fun `test parallel OBBs`() {
        val obb1 = OBB(
            center = ObbPoint(0f, 0f),
            size = Size(10f, 10f),
            rotation = 45f
        )

        val obb2 = OBB(
            center = ObbPoint(15f, 15f),
            size = Size(10f, 10f),
            rotation = 45f
        )

        assertFalse(obb1.intersects(obb2), "Parallel OBBs should not intersect when separated")

        // Test with touching parallel OBBs
        // For 45-degree rotation, the distance between centers should be 10/sqrt(2) ≈ 7.07
        val touchingObb = OBB(
            center = ObbPoint(7.07f, 7.07f),
            size = Size(10f, 10f),
            rotation = 45f
        )

        assertTrue(obb1.intersects(touchingObb), "Parallel OBBs should intersect when touching")

        // Test with different sizes but same rotation
        val obb4 = OBB(
            center = ObbPoint(0f, 0f),
            size = Size(20f, 20f),
            rotation = 45f
        )

        assertTrue(obb1.intersects(obb4), "OBBs with same rotation but different sizes should intersect")
    }

    @Test
    fun `test edge cases with rotation`() {
        // Test with very small rotation
        val obb1 = OBB(
            center = ObbPoint(0f, 0f),
            size = Size(10f, 10f),
            rotation = 0.001f
        )

        val obb2 = OBB(
            center = ObbPoint(0f, 0f),
            size = Size(10f, 10f),
            rotation = 0f
        )

        assertTrue(obb1.intersects(obb2), "OBBs with very small rotation difference should intersect")

        // Test with very large rotation
        val obb3 = OBB(
            center = ObbPoint(0f, 0f),
            size = Size(10f, 10f),
            rotation = 360000f
        )

        assertTrue(obb2.intersects(obb3), "OBBs with very large rotation should be equivalent to 0 rotation")
    }

    @Test
    fun `test rotation sweep intersection`() {
        // Create a fixed OBB in the center
        val fixedObb = OBB(
            center = ObbPoint(0f, 0f),
            size = Size(10f, 10f),
            rotation = 0f
        )

        // Create an array of OBBs that will rotate around the fixed one
        val rotatingObbs = listOf(
            // Should always intersect (centers coincide)
            OBB(ObbPoint(0f, 0f), Size(10f, 10f), 0f),
            // Should sometimes intersect (close to center)
            OBB(ObbPoint(5f, 5f), Size(10f, 10f), 0f),
            // Should sometimes intersect (on the boundary)
            OBB(ObbPoint(10f, 10f), Size(10f, 10f), 0f),
            // Should never intersect (too far)
            OBB(ObbPoint(20f, 20f), Size(10f, 10f), 0f)
        )

        // Number of steps for a full rotation
        val steps = 360
        val angleStep = 360f / steps

        // Counters for statistics
        val intersectionStats = rotatingObbs.map { mutableMapOf<Boolean, Int>() }

        // Check for each angle
        for (step in 0 until steps) {
            val angle = step * angleStep

            // Check each rotating OBB
            rotatingObbs.forEachIndexed { index, obb ->
                // Create a new OBB with current rotation angle
                val rotatedObb = OBB(
                    center = obb.center,
                    size = obb.size,
                    rotation = angle
                )

                // Check intersection
                val intersects = fixedObb.intersects(rotatedObb)

                // Update statistics
                intersectionStats[index][intersects] = (intersectionStats[index][intersects] ?: 0) + 1

                // Check expected behavior
                when (index) {
                    0 -> assertTrue(intersects, "Centered OBB should always intersect at angle $angle")
                    3 -> assertFalse(intersects, "Distant OBB should never intersect at angle $angle")
                }
            }
        }

        // Print statistics
        println("\nIntersection statistics for full rotation:")
        rotatingObbs.forEachIndexed { index, obb ->
            val stats = intersectionStats[index]
            val total = stats.values.sum()
            val intersectionPercentage = (stats[true] ?: 0) * 100.0 / total
            println("OBB $index (center: ${obb.center}, size: ${obb.size}):")
            println("  Intersections: ${stats[true] ?: 0} (${intersectionPercentage.toInt()}%)")
            println("  Non-intersections: ${stats[false] ?: 0} (${(100 - intersectionPercentage).toInt()}%)")
        }

        // Verify that statistics match expectations
        assertTrue(intersectionStats[0][true] == steps, "Centered OBB should intersect at all angles")
        assertTrue(intersectionStats[3][false] == steps, "Distant OBB should never intersect")

        // Verify that boundary OBB has a reasonable number of intersections
        val borderIntersections = intersectionStats[2][true] ?: 0
        assertTrue(borderIntersections > 0, "Border OBB should intersect at some angles")
        assertTrue(borderIntersections < steps, "Border OBB should not intersect at all angles")
    }
} 