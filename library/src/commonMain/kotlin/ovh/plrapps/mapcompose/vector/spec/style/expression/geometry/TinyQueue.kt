package ovh.plrapps.mapcompose.vector.spec.style.expression.geometry

/**
 * Binary heap, ported from the `tinyqueue` package MapLibre depends on.
 *
 * [pop] returns the element that sorts *first* under [comparator]. The `distance` expression
 * deliberately supplies a descending comparator, so its queue yields the largest bbox distance
 * first; that behaviour is preserved here.
 */
class TinyQueue<T>(
    initial: List<T> = emptyList(),
    private val comparator: Comparator<T>,
) {
    private val data: MutableList<T> = initial.toMutableList()

    val size: Int get() = data.size

    init {
        if (data.size > 1) {
            for (i in (data.size / 2 - 1) downTo 0) down(i)
        }
    }

    fun push(item: T) {
        data.add(item)
        up(data.size - 1)
    }

    fun pop(): T? {
        if (data.isEmpty()) return null
        val top = data[0]
        val bottom = data.removeAt(data.size - 1)
        if (data.isNotEmpty()) {
            data[0] = bottom
            down(0)
        }
        return top
    }

    private fun up(posIn: Int) {
        var pos = posIn
        val item = data[pos]
        while (pos > 0) {
            val parent = (pos - 1) shr 1
            val current = data[parent]
            if (comparator.compare(item, current) >= 0) break
            data[pos] = current
            pos = parent
        }
        data[pos] = item
    }

    private fun down(posIn: Int) {
        var pos = posIn
        val halfLength = data.size shr 1
        val item = data[pos]

        while (pos < halfLength) {
            var bestChild = (pos shl 1) + 1
            val right = bestChild + 1
            if (right < data.size && comparator.compare(data[right], data[bestChild]) < 0) {
                bestChild = right
            }
            if (comparator.compare(data[bestChild], item) >= 0) break
            data[pos] = data[bestChild]
            pos = bestChild
        }

        data[pos] = item
    }
}
