package ovh.plrapps.mapcompose.vector.utils

/**
 * A map keyed by `Long` that boxes nothing: open addressing over a `LongArray` of keys and an array
 * of values.
 *
 * The placement pass keeps a decision and a fade state per symbol identity, for every symbol of
 * every bucket, and rebuilds both maps on each cycle -- a few times a second through a gesture. In
 * a `MutableMap<Long, V>` each entry was a boxed key and a linked node beside the value, tens of
 * thousands of them per cycle, which was most of what the vector layer allocated while the map
 * moved.
 *
 * Not thread-safe, and deliberately small: no removal, because nothing here removes.
 */
internal class LongMap<V : Any>(expectedSize: Int = 16) {
    private var keys = LongArray(capacityFor(expectedSize))
    private var values = arrayOfNulls<Any>(keys.size)

    /* Key 0 marks an empty slot, so an entry *for* 0 lives here instead. */
    private var zeroValue: V? = null

    var size = 0
        private set

    @Suppress("UNCHECKED_CAST")
    operator fun get(key: Long): V? {
        if (key == 0L) return zeroValue
        val mask = keys.size - 1
        var slot = slotOf(key, mask)
        while (true) {
            val found = keys[slot]
            if (found == key) return values[slot] as V
            if (found == 0L) return null
            slot = (slot + 1) and mask
        }
    }

    operator fun contains(key: Long): Boolean = get(key) != null

    operator fun set(key: Long, value: V) {
        if (key == 0L) {
            if (zeroValue == null) size++
            zeroValue = value
            return
        }
        // Kept at most half full, which keeps a probe sequence short.
        if ((size + 1) * 2 > keys.size) grow()
        val mask = keys.size - 1
        var slot = slotOf(key, mask)
        while (true) {
            val found = keys[slot]
            if (found == key) {
                values[slot] = value
                return
            }
            if (found == 0L) {
                keys[slot] = key
                values[slot] = value
                size++
                return
            }
            slot = (slot + 1) and mask
        }
    }

    /** Every entry, in no particular order. */
    @Suppress("UNCHECKED_CAST")
    inline fun forEach(action: (key: Long, value: V) -> Unit) {
        zeroEntry()?.let { action(0L, it) }
        val keys = rawKeys()
        val values = rawValues()
        for (slot in keys.indices) {
            val key = keys[slot]
            if (key != 0L) action(key, values[slot] as V)
        }
    }

    @PublishedApi internal fun rawKeys(): LongArray = keys
    @PublishedApi internal fun rawValues(): Array<Any?> = values
    @PublishedApi internal fun zeroEntry(): V? = zeroValue

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(oldKeys.size * 2)
        values = arrayOfNulls(keys.size)
        val mask = keys.size - 1
        for (i in oldKeys.indices) {
            val key = oldKeys[i]
            if (key == 0L) continue
            var slot = slotOf(key, mask)
            while (keys[slot] != 0L) slot = (slot + 1) and mask
            keys[slot] = key
            values[slot] = oldValues[i]
        }
    }
}

/** A set of `Long`s that boxes nothing; see [LongMap]. Unlike the map, it can forget a key. */
internal class LongSet(expectedSize: Int = 16) {
    private var keys = LongArray(capacityFor(expectedSize))
    private var hasZero = false

    var size = 0
        private set

    operator fun contains(key: Long): Boolean {
        if (key == 0L) return hasZero
        val mask = keys.size - 1
        var slot = slotOf(key, mask)
        while (true) {
            val found = keys[slot]
            if (found == key) return true
            if (found == 0L) return false
            slot = (slot + 1) and mask
        }
    }

    /** Adds [key], and says whether it was new -- `MutableSet.add`'s contract. */
    fun add(key: Long): Boolean {
        if (key == 0L) {
            if (hasZero) return false
            hasZero = true
            size++
            return true
        }
        if ((size + 1) * 2 > keys.size) grow()
        val mask = keys.size - 1
        var slot = slotOf(key, mask)
        while (true) {
            val found = keys[slot]
            if (found == key) return false
            if (found == 0L) {
                keys[slot] = key
                size++
                return true
            }
            slot = (slot + 1) and mask
        }
    }

    /** Removes [key], and says whether it was there -- `MutableSet.remove`'s contract. */
    fun remove(key: Long): Boolean {
        if (key == 0L) {
            if (!hasZero) return false
            hasZero = false
            size--
            return true
        }
        val mask = keys.size - 1
        var gap = slotOf(key, mask)
        while (true) {
            val found = keys[gap]
            if (found == key) break
            if (found == 0L) return false
            gap = (gap + 1) and mask
        }
        /* Close the gap, so that no probe sequence is cut in two by it: every key after it, up to
         * the next empty slot, moves back into it unless that would put it before its own home. */
        var next = (gap + 1) and mask
        while (keys[next] != 0L) {
            val home = slotOf(keys[next], mask)
            if (((next - home) and mask) >= ((next - gap) and mask)) {
                keys[gap] = keys[next]
                gap = next
            }
            next = (next + 1) and mask
        }
        keys[gap] = 0L
        size--
        return true
    }

    /** Every key, in no particular order. */
    inline fun forEach(action: (key: Long) -> Unit) {
        if (containsZero()) action(0L)
        for (key in rawKeys()) if (key != 0L) action(key)
    }

    @PublishedApi internal fun rawKeys(): LongArray = keys
    @PublishedApi internal fun containsZero(): Boolean = hasZero

    private fun grow() {
        val oldKeys = keys
        keys = LongArray(oldKeys.size * 2)
        val mask = keys.size - 1
        for (key in oldKeys) {
            if (key == 0L) continue
            var slot = slotOf(key, mask)
            while (keys[slot] != 0L) slot = (slot + 1) and mask
            keys[slot] = key
        }
    }
}

/** The smallest power of two that holds [expectedSize] entries half full. */
private fun capacityFor(expectedSize: Int): Int {
    var capacity = 16
    while (capacity < expectedSize * 2) capacity = capacity shl 1
    return capacity
}

/* Fibonacci hashing: cross-tile ids are consecutive integers, and the multiply spreads them. */
private fun slotOf(key: Long, mask: Int): Int =
    ((key * -0x61c8864680b583ebL) ushr 32).toInt() and mask
