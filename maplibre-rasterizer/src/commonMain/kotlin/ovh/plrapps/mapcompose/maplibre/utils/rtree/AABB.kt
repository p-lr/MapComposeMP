package ovh.plrapps.mapcompose.maplibre.utils.rtree

data class AABB(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
) {
    init {
        require(maxX >= minX && maxY >= minY) { "Invalid AABB: max coordinates must be greater than or equal to min coordinates" }
    }

    val isPoint: Boolean = minX == maxX && minY == maxY

    private val cachedArea: Float = (maxX - minX) * (maxY - minY)

    private var cachedUnion: AABB? = null
    private var cachedUnionWith: AABB? = null

    fun intersects(other: AABB): Boolean {
        val intersectsX = maxX >= other.minX && minX <= other.maxX
        val intersectsY = maxY >= other.minY && minY <= other.maxY

        return intersectsX && intersectsY
    }

    fun contains(other: AABB): Boolean {
        return minX <= other.minX &&
                maxX >= other.maxX &&
                minY <= other.minY &&
                maxY >= other.maxY
    }

    fun union(other: AABB): AABB {
        if (cachedUnionWith == other) {
            return cachedUnion ?: run {
                val result = AABB(
                    minOf(minX, other.minX),
                    minOf(minY, other.minY),
                    maxOf(maxX, other.maxX),
                    maxOf(maxY, other.maxY)
                )
                cachedUnion = result
                result
            }
        }
        cachedUnionWith = other
        cachedUnion = AABB(
            minOf(minX, other.minX),
            minOf(minY, other.minY),
            maxOf(maxX, other.maxX),
            maxOf(maxY, other.maxY)
        )
        return cachedUnion!!
    }

    fun area(): Float = cachedArea
}