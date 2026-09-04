package ovh.plrapps.mapcompose.vector.renderer.collision

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ovh.plrapps.mapcompose.vector.utils.obb.OBB
import ovh.plrapps.mapcompose.vector.utils.obb.ObbPoint
import ovh.plrapps.mapcompose.vector.utils.obb.Size as ObbSize
import ovh.plrapps.mapcompose.vector.utils.rtree.AABB

class CollisionDetectorTest {
    private fun label(
        id: String,
        x: Float, y: Float, w: Float, h: Float,
        overlapMode: OverlapMode = OverlapMode.Never,
        ignorePlacement: Boolean = false,
        rotation: Float = 0f,
        circles: List<CollisionCircle>? = null,
    ) = LabelPlacement(
        text = id,
        position = ObbPoint(x + w / 2, y + h / 2),
        angle = rotation,
        bounds = Rect(x, y, x + w, y + h),
        obb = OBB(
            center = ObbPoint(x + w / 2, y + h / 2),
            size = ObbSize(w, h),
            rotation = rotation
        ),
        layerIndex = 0,
        inLayerPriority = 0.0,
        overlapMode = overlapMode,
        ignorePlacement = ignorePlacement,
        circles = circles,
    )

    /** A chain of circles along a quarter-circle arc, the shape a curved road label really covers. */
    private fun arcChain(
        centerX: Float, centerY: Float, radius: Float, circleRadius: Float, count: Int,
    ): List<CollisionCircle> = (0 until count).map { i ->
        val t = i.toFloat() / (count - 1)
        val angle = t * (kotlin.math.PI.toFloat() / 2f)
        CollisionCircle(
            x = centerX + radius * kotlin.math.cos(angle),
            y = centerY + radius * kotlin.math.sin(angle),
            radius = circleRadius,
        )
    }

    // region the index's own box test

    @Test
    fun testAabbIntersectsTrue() {
        val r1 = AABB(0f, 0f, 10f, 10f)
        val r2 = AABB(5f, 5f, 15f, 15f)
        assertTrue(r1.intersects(r2))
    }

    @Test
    fun testAabbIntersectsFalse() {
        val r1 = AABB(0f, 0f, 10f, 10f)
        val r2 = AABB(11f, 11f, 20f, 20f)
        assertFalse(r1.intersects(r2))
    }

    @Test
    fun testTouchingBoxesCollide() {
        // Upstream's `_queryCell` compares with `<=` / `>=`, so boxes sharing an edge collide.
        val r1 = AABB(0f, 0f, 10f, 10f)
        val r2 = AABB(10f, 0f, 20f, 10f)
        assertTrue(r1.intersects(r2))

        val detector = CollisionDetector()
        assertTrue(detector.tryPlaceLabel(label("A", 0f, 0f, 10f, 10f)))
        assertFalse(detector.tryPlaceLabel(label("B", 10f, 0f, 10f, 10f)))
    }

    // endregion

    // region overlap modes

    @Test
    fun testNeverBlocksNever() {
        val detector = CollisionDetector()
        val p1 = label("A", 0f, 0f, 10f, 10f, OverlapMode.Never)
        val p2 = label("B", 5f, 5f, 10f, 10f, OverlapMode.Never)

        assertTrue(detector.tryPlaceLabel(p1))
        assertFalse(detector.tryPlaceLabel(p2)) // collides with p1
    }

    @Test
    fun testAlwaysIsPlacedAndBlocksNever() {
        val detector = CollisionDetector()
        val p1 = label("A", 0f, 0f, 10f, 10f, OverlapMode.Always)
        val p2 = label("B", 5f, 5f, 10f, 10f, OverlapMode.Never)

        assertTrue(detector.tryPlaceLabel(p1))   // always placed
        assertFalse(detector.tryPlaceLabel(p2))  // p1 (Always) blocks p2 (Never)
    }

    @Test
    fun testAlwaysDoesNotBlockAlways() {
        val detector = CollisionDetector()
        val p1 = label("A", 0f, 0f, 10f, 10f, OverlapMode.Always)
        val p2 = label("B", 5f, 5f, 10f, 10f, OverlapMode.Always)

        assertTrue(detector.tryPlaceLabel(p1))
        assertTrue(detector.tryPlaceLabel(p2)) // Always is never blocked
    }

    @Test
    fun testCooperativeDoesNotBlockCooperative() {
        val detector = CollisionDetector()
        val p1 = label("A", 0f, 0f, 10f, 10f, OverlapMode.Cooperative)
        val p2 = label("B", 5f, 5f, 10f, 10f, OverlapMode.Cooperative)

        assertTrue(detector.tryPlaceLabel(p1))
        assertTrue(detector.tryPlaceLabel(p2)) // two Cooperative symbols can overlap
    }

    @Test
    fun testNeverBlocksCooperative() {
        val detector = CollisionDetector()
        val p1 = label("A", 0f, 0f, 10f, 10f, OverlapMode.Never)
        val p2 = label("B", 5f, 5f, 10f, 10f, OverlapMode.Cooperative)

        assertTrue(detector.tryPlaceLabel(p1))
        assertFalse(detector.tryPlaceLabel(p2)) // Never blocks Cooperative
    }

    @Test
    fun testCooperativeBlocksNever() {
        val detector = CollisionDetector()
        val p1 = label("A", 0f, 0f, 10f, 10f, OverlapMode.Cooperative)
        val p2 = label("B", 5f, 5f, 10f, 10f, OverlapMode.Never)

        assertTrue(detector.tryPlaceLabel(p1))
        assertFalse(detector.tryPlaceLabel(p2)) // Cooperative is in tree → blocks Never
    }

    @Test
    fun testIgnorePlacementDoesNotBlockOthers() {
        val detector = CollisionDetector()
        val p1 = label("A", 0f, 0f, 10f, 10f, ignorePlacement = true)
        val p2 = label("B", 5f, 5f, 10f, 10f, OverlapMode.Never)

        assertTrue(detector.tryPlaceLabel(p1))  // nothing placed yet
        assertTrue(detector.tryPlaceLabel(p2))  // p1 not in tree → doesn't block p2
    }

    @Test
    fun testIgnorePlacementIsStillTestedAgainstOthers() {
        // `*-ignore-placement` means "others may overlap me", not "I may overlap others":
        // upstream's insertCollisionBox only routes the box to ignoredGrid, and placeCollisionBox
        // still hit-tests it against the real grid.
        val detector = CollisionDetector()
        val p1 = label("A", 0f, 0f, 10f, 10f, OverlapMode.Never)
        val p2 = label("B", 5f, 5f, 10f, 10f, OverlapMode.Never, ignorePlacement = true)

        assertTrue(detector.tryPlaceLabel(p1))
        assertFalse(detector.tryPlaceLabel(p2))
    }

    @Test
    fun testIgnorePlacementWithAllowOverlapIsAlwaysPlaced() {
        // Only `*-allow-overlap` exempts a symbol from the test; combined with ignore-placement it
        // is both unconditionally placed and invisible to everyone else.
        val detector = CollisionDetector()
        val p1 = label("A", 0f, 0f, 10f, 10f, OverlapMode.Never)
        val p2 = label("B", 5f, 5f, 10f, 10f, OverlapMode.Always, ignorePlacement = true)
        val p3 = label("C", 5f, 5f, 10f, 10f, OverlapMode.Never)

        assertTrue(detector.tryPlaceLabel(p1))
        assertTrue(detector.tryPlaceLabel(p2))
        assertFalse(detector.tryPlaceLabel(p3)) // blocked by p1, never by p2
    }

    @Test
    fun testNonOverlappingNeverSymbolsAreAllPlaced() {
        val detector = CollisionDetector()
        val p1 = label("A", 0f, 0f, 10f, 10f, OverlapMode.Never)
        val p2 = label("B", 20f, 0f, 10f, 10f, OverlapMode.Never) // no overlap

        assertTrue(detector.tryPlaceLabel(p1))
        assertTrue(detector.tryPlaceLabel(p2))
    }

    // endregion

    // region rotated boxes

    @Test
    fun testRotatedBoxesWhoseAabbsOverlapAreBothPlaced() {
        // Two long thin labels at right angles, offset so their bodies miss but their enclosing
        // AABBs overlap. Upstream, whose boxes are the AABBs, would reject the second; this port
        // keeps the real orientation and places both.
        val detector = CollisionDetector()
        val a = label("A", 0f, 0f, 60f, 4f, rotation = 45f)
        val b = label("B", 0f, 0f, 60f, 4f, rotation = -45f)

        assertTrue(a.obb.getAABB().intersects(b.obb.getAABB()))
        assertTrue(detector.tryPlaceLabel(a))
        assertFalse(detector.tryPlaceLabel(b)) // they cross at their common centre

        val detector2 = CollisionDetector()
        val c = label("C", 0f, 0f, 60f, 4f, rotation = 45f)
        val d = label("D", 40f, -40f, 60f, 4f, rotation = -45f)
        assertTrue(c.obb.getAABB().intersects(d.obb.getAABB()))
        assertTrue(detector2.tryPlaceLabel(c))
        assertTrue(detector2.tryPlaceLabel(d)) // bodies miss, only the AABBs overlap
    }

    // endregion

    // region the two-phase contract

    @Test
    fun testAnIconAndItsLabelAreTestedApartAndAdmittedTogether() {
        // This is why `wouldCollide` and `insert` are separate: padded icon and text boxes always
        // overlap each other, so `tryPlaceLabel` on the pair would reject the second half.
        val detector = CollisionDetector()
        val icon = label("icon", 0f, 0f, 20f, 20f)
        val text = label("text", 10f, 0f, 40f, 12f)

        assertFalse(detector.wouldCollide(icon))
        assertFalse(detector.wouldCollide(text))
        detector.insert(icon)
        detector.insert(text)

        // Both are in the index afterwards.
        assertTrue(detector.wouldCollide(label("other", 2f, 2f, 6f, 6f)))
        assertTrue(detector.wouldCollide(label("other", 40f, 2f, 6f, 6f)))
    }

    @Test
    fun testWouldCollideDoesNotInsert() {
        val detector = CollisionDetector()
        val a = label("A", 0f, 0f, 10f, 10f)
        assertFalse(detector.wouldCollide(a))
        // Nothing was admitted, so an overlapping label is still free to take the ground.
        assertTrue(detector.tryPlaceLabel(label("B", 5f, 5f, 10f, 10f)))
    }

    // endregion

    // region the padded viewport

    @Test
    fun testASymbolInsideThePaddingStillCompetes() {
        val detector = CollisionDetector(viewportWidth = 800f, viewportHeight = 600f)
        // Centre at x = -50, entirely off screen but inside the 100 px margin.
        val a = label("A", -70f, 290f, 40f, 20f)
        assertTrue(detector.tryPlaceLabel(a))
        assertFalse(detector.tryPlaceLabel(label("B", -70f, 290f, 40f, 20f)))
    }

    @Test
    fun testASymbolBeyondThePaddingIsRefusedAndBlocksNobody() {
        val detector = CollisionDetector(viewportWidth = 800f, viewportHeight = 600f)
        val far = label("far", -420f, 290f, 40f, 20f)
        assertFalse(detector.tryPlaceLabel(far))
        // Refused, so it was never indexed: a label that reaches into the grid and overlaps the
        // refused one is still free to take the ground.
        val wide = label("wide", -420f, 285f, 430f, 30f)
        assertTrue(detector.isInsideGrid(wide.obb.getAABB()))
        assertTrue(wide.obb.getAABB().intersects(far.obb.getAABB()))
        assertTrue(detector.tryPlaceLabel(wide))
    }

    @Test
    fun testAnAllowOverlapSymbolBeyondThePaddingIsStillRefused() {
        // Upstream folds `!isInsideGrid` into `unplaceable` before the overlap mode is consulted.
        val detector = CollisionDetector(viewportWidth = 800f, viewportHeight = 600f)
        assertFalse(detector.tryPlaceLabel(label("A", -420f, 290f, 40f, 20f, OverlapMode.Always)))
    }

    @Test
    fun testAnUnboundedDetectorAcceptsAnythingAnywhere() {
        val detector = CollisionDetector()
        assertTrue(detector.tryPlaceLabel(label("A", -100_000f, -100_000f, 40f, 20f)))
    }

    // endregion

    // region circle chains

    @Test
    fun testTwoOverlappingChainsCollide() {
        val detector = CollisionDetector()
        val a = label("A", 0f, 0f, 1f, 1f, circles = listOf(
            CollisionCircle(0f, 0f, 5f), CollisionCircle(10f, 0f, 5f),
        ))
        val b = label("B", 0f, 0f, 1f, 1f, circles = listOf(
            CollisionCircle(12f, 3f, 5f), CollisionCircle(22f, 3f, 5f),
        ))
        assertTrue(detector.tryPlaceLabel(a))
        assertFalse(detector.tryPlaceLabel(b))
    }

    @Test
    fun testTwoDisjointChainsAreBothPlaced() {
        val detector = CollisionDetector()
        val a = label("A", 0f, 0f, 1f, 1f, circles = listOf(
            CollisionCircle(0f, 0f, 5f), CollisionCircle(10f, 0f, 5f),
        ))
        val b = label("B", 0f, 0f, 1f, 1f, circles = listOf(
            CollisionCircle(0f, 40f, 5f), CollisionCircle(10f, 40f, 5f),
        ))
        assertTrue(detector.tryPlaceLabel(a))
        assertTrue(detector.tryPlaceLabel(b))
    }

    @Test
    fun testAChainCollidesWithABoxAndABoxWithAChain() {
        val chain = label("chain", 0f, 0f, 1f, 1f, circles = listOf(
            CollisionCircle(0f, 0f, 5f), CollisionCircle(10f, 0f, 5f),
        ))
        val box = label("box", 8f, -3f, 10f, 6f)

        val chainFirst = CollisionDetector()
        assertTrue(chainFirst.tryPlaceLabel(chain))
        assertFalse(chainFirst.tryPlaceLabel(box))

        val boxFirst = CollisionDetector()
        assertTrue(boxFirst.tryPlaceLabel(box))
        assertFalse(boxFirst.tryPlaceLabel(chain))
    }

    @Test
    fun testAChainRespectsTheOverlapModes() {
        val chained = { mode: OverlapMode, ignore: Boolean ->
            label("c", 0f, 0f, 1f, 1f, mode, ignore, circles = listOf(CollisionCircle(0f, 0f, 5f)))
        }

        val always = CollisionDetector()
        assertTrue(always.tryPlaceLabel(chained(OverlapMode.Never, false)))
        assertTrue(always.tryPlaceLabel(chained(OverlapMode.Always, false)))

        val cooperative = CollisionDetector()
        assertTrue(cooperative.tryPlaceLabel(chained(OverlapMode.Cooperative, false)))
        assertTrue(cooperative.tryPlaceLabel(chained(OverlapMode.Cooperative, false)))

        val ignored = CollisionDetector()
        assertTrue(ignored.tryPlaceLabel(chained(OverlapMode.Never, true)))
        assertTrue(ignored.tryPlaceLabel(chained(OverlapMode.Never, false)))
    }

    @Test
    fun testACurvedChainLeavesRoomItsStraightBoxWouldHaveTaken() {
        // The regression this shape exists for: a label along a curve covers the curve, not the
        // rectangle spanning it. The neighbour sits inside the arc's enclosing box but off the arc.
        val arc = arcChain(centerX = 0f, centerY = 0f, radius = 60f, circleRadius = 6f, count = 12)
        val neighbour = label("N", 5f, 5f, 20f, 12f)

        val withChain = CollisionDetector()
        assertTrue(withChain.tryPlaceLabel(label("arc", 0f, 0f, 1f, 1f, circles = arc)))
        assertTrue(withChain.tryPlaceLabel(neighbour))

        // The same label as one straight box spanning the arc does block it.
        val asBox = CollisionDetector()
        assertTrue(asBox.tryPlaceLabel(label("arc", 0f, 0f, 60f, 60f)))
        assertFalse(asBox.tryPlaceLabel(neighbour))
    }

    @Test
    fun testAChainBeyondThePaddingIsRefused() {
        val detector = CollisionDetector(viewportWidth = 800f, viewportHeight = 600f)
        val far = label("far", 0f, 0f, 1f, 1f, circles = listOf(
            CollisionCircle(-400f, 300f, 5f), CollisionCircle(-390f, 300f, 5f),
        ))
        assertFalse(detector.tryPlaceLabel(far))
    }

    // endregion
}
