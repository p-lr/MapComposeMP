package ovh.plrapps.mapcompose.utils

fun <T> MutableCollection<T>.removeFirst(predicate: (T) -> Boolean): Boolean {
    var removed = false
    val it = iterator()
    while (it.hasNext()) {
        if (predicate(it.next())) {
            it.remove()
            removed = true
            break
        }
    }
    return removed
}

fun <T> MutableList<T>.swap(i1: Int, i2: Int): MutableList<T> = apply {
    val t = this[i1]
    this[i1] = this[i2]
    this[i2] = t
}