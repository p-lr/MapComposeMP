package ovh.plrapps.mapcompose.vector.renderer

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureCullTest {

    private val cull = FeatureCull(windowLeft = 100f, windowTop = 0f, windowRight = 200f, windowBottom = 100f)

    private fun box(minX: Float, minY: Float, maxX: Float, maxY: Float) = floatArrayOf(minX, minY, maxX, maxY)

    @Test
    fun `a box inside or across the window reaches it`() {
        assertTrue(cull.select(box(120f, 10f, 150f, 20f), 0).reaches(0f))
        assertTrue(cull.select(box(50f, 10f, 150f, 20f), 0).reaches(0f))
    }

    @Test
    fun `a box beside the window reaches it only through its reach`() {
        val beside = box(80f, 10f, 95f, 20f)
        assertFalse(cull.select(beside, 0).reaches(4f), "5 px short of the window")
        assertTrue(cull.select(beside, 0).reaches(6f), "a 6 px stroke crosses into it")
    }

    @Test
    fun `a feature with no geometry never reaches`() {
        val empty = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        assertFalse(cull.select(empty, 0).reaches(1000f))
    }

    @Test
    fun `the selected index picks its own box`() {
        val boxes = box(0f, 0f, 10f, 10f) + box(150f, 50f, 160f, 60f)
        assertFalse(cull.select(boxes, 0).reaches(0f))
        assertTrue(cull.select(boxes, 1).reaches(0f))
    }
}
