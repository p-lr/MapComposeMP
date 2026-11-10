package ovh.plrapps.mapcompose.vector.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [LongMap] and [LongSet] against the boxed collections they replace in the placement pass. */
class LongMapTest {

    /** Keys chosen to collide, to wrap round the table, and to force several growths. */
    private val keys: List<Long> =
        (1L..3000L).toList() + listOf(0L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 1L shl 40, (1L shl 40) + 16)

    @Test
    fun `a map answers what a boxed map answers`() {
        val expected = mutableMapOf<Long, String>()
        val map = LongMap<String>()
        for (key in keys) {
            expected[key] = "v$key"
            map[key] = "v$key"
        }
        // Overwrites must not add entries.
        for (key in keys.take(500)) {
            expected[key] = "w$key"
            map[key] = "w$key"
        }

        assertEquals(expected.size, map.size)
        for (key in keys) assertEquals(expected[key], map[key], "value of $key")
        assertNull(map[4242L])
        assertFalse(5000L in map)
        assertTrue(0L in map)

        val seen = mutableMapOf<Long, String>()
        map.forEach { key, value -> seen[key] = value }
        assertEquals(expected, seen)
    }

    @Test
    fun `an empty map has nothing in it`() {
        val map = LongMap<String>()

        assertEquals(0, map.size)
        assertNull(map[0L])
        assertNull(map[1L])
        var visited = 0
        map.forEach { _, _ -> visited++ }
        assertEquals(0, visited)
    }

    @Test
    fun `a set says whether a key was new`() {
        val expected = mutableSetOf<Long>()
        val set = LongSet()
        for (key in keys + keys.take(100)) {
            assertEquals(expected.add(key), set.add(key), "adding $key")
        }

        assertEquals(expected.size, set.size)
        for (key in keys) assertTrue(key in set)
        assertFalse(4242L in set)
    }

    @Test
    fun `a set forgets a key and still finds the ones that probed past it`() {
        val expected = mutableSetOf<Long>()
        val set = LongSet()
        // Consecutive ids, as cross-tile ids are, plus the keys above: plenty of shared probe runs.
        val all = keys + (1L..600L)
        for (key in all) {
            expected.add(key)
            set.add(key)
        }
        for ((position, key) in all.withIndex()) {
            if (position % 3 == 0) continue
            assertEquals(expected.remove(key), set.remove(key), "removing $key")
            assertEquals(expected.remove(key), set.remove(key), "removing $key again")
        }
        assertFalse(set.remove(4242L))

        assertEquals(expected.size, set.size)
        for (key in all) assertEquals(key in expected, key in set, "holding $key")
        val visited = mutableSetOf<Long>()
        set.forEach { visited.add(it) }
        assertEquals(expected, visited)

        // And it takes them back.
        for (key in all) assertEquals(expected.add(key), set.add(key), "adding $key back")
        assertEquals(expected.size, set.size)
    }
}
