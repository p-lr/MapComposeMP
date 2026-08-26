package ovh.plrapps.mapcompose.vector.spec.style.expression.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [TinyQueue] is a port of the `tinyqueue` package, which ships no test file into this repo.
 * `Distance` depends on its ordering being exactly as upstream configures it.
 */
class TinyQueueTest {

    private val ascending = Comparator<Int> { a, b -> a.compareTo(b) }

    private fun drain(queue: TinyQueue<Int>): List<Int> = buildList {
        while (queue.size > 0) add(queue.pop()!!)
    }

    @Test
    fun `pops in comparator order`() {
        val queue = TinyQueue(comparator = ascending)
        for (value in listOf(5, 1, 4, 2, 3)) queue.push(value)
        assertEquals(listOf(1, 2, 3, 4, 5), drain(queue))
    }

    @Test
    fun `heapifies a pre-seeded list`() {
        val queue = TinyQueue(listOf(9, 3, 7, 1, 8, 2), ascending)
        assertEquals(6, queue.size)
        assertEquals(listOf(1, 2, 3, 7, 8, 9), drain(queue))
    }

    @Test
    fun `pop on an empty queue returns null`() {
        val queue = TinyQueue(comparator = ascending)
        assertNull(queue.pop())
        queue.push(1)
        assertEquals(1, queue.pop())
        assertNull(queue.pop())
    }

    @Test
    fun `keeps duplicates`() {
        val queue = TinyQueue(listOf(2, 2, 1, 2), ascending)
        assertEquals(listOf(1, 2, 2, 2), drain(queue))
    }

    /**
     * `Distance` deliberately supplies a descending comparator, so its queue yields the *largest*
     * bounding-box distance first. Surprising, but it is what upstream does, and the search's
     * pruning depends on it.
     */
    @Test
    fun `a descending comparator yields the largest element first`() {
        val descending = Comparator<Double> { a, b -> b.compareTo(a) }
        val queue = TinyQueue(listOf(1.0, 5.0, 3.0), descending)
        assertEquals(5.0, queue.pop())
        assertEquals(3.0, queue.pop())
        assertEquals(1.0, queue.pop())
    }
}
