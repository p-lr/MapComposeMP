package ovh.plrapps.mapcompose.vector.renderer

import ovh.plrapps.mapcompose.vector.spec.Tile
import kotlin.concurrent.Volatile

/**
 * An MVT feature's properties, read straight off its tags rather than copied into a map.
 *
 * Building a `LinkedHashMap` per feature -- per tile render, and again per symbol bucket -- was a
 * fifth of everything the tile renderer allocated, and most of those maps were consulted for one or
 * two keys by a filter and never again. A lookup here walks the feature's tag pairs (a handful) for
 * the key's index, which the layer resolves once ([Tile.Layer.keyIndex]), and returns the layer's
 * value already converted for the engine ([Tile.Layer.engineValues]).
 *
 * It answers exactly what the copied map did: the *last* pair naming a key wins, as a later `put`
 * replaced an earlier one, an out-of-range index is skipped, and iterating builds that map once.
 */
internal class MvtFeatureProperties(
    private val layer: Tile.Layer,
    private val tags: IntArray,
) : AbstractMap<String, Any?>() {

    override fun get(key: String): Any? {
        val index = indexOfLast(key)
        return if (index < 0) null else layer.engineValues[tags[index + 1]]
    }

    override fun containsKey(key: String): Boolean = indexOfLast(key) >= 0

    /* Built on first iteration -- rare: `["properties"]`, a token substitution walk -- and kept. A
     * field rather than `by lazy`, which cost two allocations on every feature of every tile. */
    @Volatile
    private var copiedEntries: Set<Map.Entry<String, Any?>>? = null

    override val entries: Set<Map.Entry<String, Any?>>
        get() = copiedEntries ?: run {
            val copy = LinkedHashMap<String, Any?>()
            val keys = layer.keys
            val values = layer.engineValues
            for (i in 0 until tags.size - 1 step 2) {
                val keyIdx = tags[i]
                val valueIdx = tags[i + 1]
                if (keyIdx < keys.size && valueIdx < values.size) copy[keys[keyIdx]] = values[valueIdx]
            }
            copy.entries.also { copiedEntries = it }
        }

    /** The position in [tags] of the last valid pair naming [key], or -1. */
    private fun indexOfLast(key: String): Int {
        val keys = layer.keys
        val valueCount = layer.values.size
        if (layer.hasDuplicateKeys) {
            var i = tags.size - 2 - (tags.size % 2)
            while (i >= 0) {
                val keyIdx = tags[i]
                if (keyIdx < keys.size && tags[i + 1] < valueCount && keys[keyIdx] == key) return i
                i -= 2
            }
            return -1
        }
        val wanted = layer.keyIndex[key] ?: return -1
        var i = tags.size - 2 - (tags.size % 2)
        while (i >= 0) {
            if (tags[i] == wanted && tags[i + 1] < valueCount) return i
            i -= 2
        }
        return -1
    }
}
