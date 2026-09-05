package ovh.plrapps.mapcompose.vector.symbol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ovh.plrapps.mapcompose.vector.utils.obb.OBB
import ovh.plrapps.mapcompose.vector.utils.obb.ObbPoint
import ovh.plrapps.mapcompose.vector.utils.obb.Size as ObbSize

/**
 * The circle side of the collision maths, against `_circlesCollide` and `_circleAndRectCollide`
 * (`symbol/grid_index.ts`), plus the circle-against-oriented-box test this port needs and upstream
 * does not have.
 */
class CollisionGeometryTest {

    // region circle against circle

    @Test
    fun `overlapping circles collide`() {
        assertTrue(circlesCollide(0f, 0f, 5f, 6f, 0f, 5f))
    }

    @Test
    fun `disjoint circles do not collide`() {
        assertFalse(circlesCollide(0f, 0f, 5f, 20f, 0f, 5f))
    }

    @Test
    fun `circles that exactly touch do not collide`() {
        // Upstream's comparison is strict: `(r1 + r2)^2 > dx^2 + dy^2`.
        assertFalse(circlesCollide(0f, 0f, 5f, 10f, 0f, 5f))
    }

    @Test
    fun `circle distance is measured on both axes`() {
        // Centres 3-4-5 apart; radii sum to 5, so this is the touching case again.
        assertFalse(circlesCollide(0f, 0f, 2f, 3f, 4f, 3f))
        assertTrue(circlesCollide(0f, 0f, 2.1f, 3f, 4f, 3f))
    }

    // endregion

    // region circle against rectangle

    @Test
    fun `a circle centred inside the rectangle collides`() {
        assertTrue(circleAndRectCollide(5f, 5f, 1f, 0f, 0f, 10f, 10f))
    }

    @Test
    fun `a circle overlapping an edge collides`() {
        assertTrue(circleAndRectCollide(-2f, 5f, 3f, 0f, 0f, 10f, 10f))
    }

    @Test
    fun `a circle far beyond an edge is rejected by the early out`() {
        assertFalse(circleAndRectCollide(-20f, 5f, 3f, 0f, 0f, 10f, 10f))
        assertFalse(circleAndRectCollide(5f, -20f, 3f, 0f, 0f, 10f, 10f))
    }

    @Test
    fun `a circle that exactly touches an edge collides`() {
        // Upstream's rectangle comparison is inclusive, unlike its circle one.
        assertTrue(circleAndRectCollide(-3f, 5f, 3f, 0f, 0f, 10f, 10f))
    }

    @Test
    fun `a circle near a corner takes the corner branch`() {
        // Within both half-extent early-outs, outside both half-extents, so only the corner
        // distance decides. Corner is (0,0); centre is 3-4-5 away from it.
        assertFalse(circleAndRectCollide(-3f, -4f, 4.9f, 0f, 0f, 10f, 10f))
        assertTrue(circleAndRectCollide(-3f, -4f, 5.1f, 0f, 0f, 10f, 10f))
    }

    // endregion

    // region circle against oriented box

    @Test
    fun `a circle hitting the face of a rotated box collides`() {
        val box = OBB(ObbPoint(0f, 0f), ObbSize(40f, 4f), 45f)
        // Along the box's own long axis, well within its length.
        val circle = CollisionCircle(10f, 10f, 2f)
        assertTrue(circleAndObbCollide(circle, box))
    }

    @Test
    fun `a circle inside the AABB but off the rotated box does not collide`() {
        val box = OBB(ObbPoint(0f, 0f), ObbSize(40f, 4f), 45f)
        // Perpendicular to the box's long axis: inside the box's enclosing AABB, far from the box.
        val circle = CollisionCircle(10f, -10f, 2f)
        assertTrue(box.getAABB().contains(circle.aabb()))
        assertFalse(circleAndObbCollide(circle, box))
    }

    @Test
    fun `a circle collides with an unrotated box exactly as with the rectangle`() {
        val box = OBB(ObbPoint(5f, 5f), ObbSize(10f, 10f), 0f)
        for (x in -5..15) {
            val circle = CollisionCircle(x.toFloat(), 5f, 3f)
            assertEquals(
                circleAndRectCollide(x.toFloat(), 5f, 3f, 0f, 0f, 10f, 10f),
                circleAndObbCollide(circle, box),
                "x = $x",
            )
        }
    }

    // endregion

    @Test
    fun `the enclosing AABB of a chain covers every circle`() {
        val chain = listOf(
            CollisionCircle(0f, 0f, 2f),
            CollisionCircle(10f, 4f, 3f),
            CollisionCircle(-5f, -1f, 1f),
        )
        val aabb = chain.enclosingAabb()
        assertEquals(-6f, aabb.minX)
        assertEquals(-2f, aabb.minY)
        assertEquals(13f, aabb.maxX)
        assertEquals(7f, aabb.maxY)
    }
}
