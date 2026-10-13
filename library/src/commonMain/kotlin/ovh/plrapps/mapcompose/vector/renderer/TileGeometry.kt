package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ovh.plrapps.mapcompose.vector.spec.Tile
import kotlin.math.max
import kotlin.math.min

/**
 * One decoded tile's geometry in canvas space, at one [geometrySize], decoded once per tile layer and
 * shared by every style layer that draws it -- and, held by `VectorRasterizer`, by every map tile an
 * overzoomed ancestor covers, since all `span²` of them decode it at the same magnified size.
 *
 * It replaces a shared 1024-entry LRU keyed by *style layer* x feature: a single tile overflowed
 * it, so the stack of style layers drawing one road (casing, fill, bridge, tunnel...) decoded the
 * road once each, and the sub-squares of an overzoomed tile -- the case the cache existed for --
 * re-decoded everything. A layer is decoded whole, under one lock, the first time any style layer
 * asks for it: a style draws most of a source layer's features through one style layer or another,
 * and one lock per layer is what a sub-square rendering on another worker can wait on cheaply.
 *
 * Each feature's bounding box comes with it, which is what lets a painter skip a feature that cannot
 * reach the part of the canvas being drawn -- see [FeatureCull].
 */
class TileGeometry(
    val geometrySize: Int,
    /** What a cache should weigh this at: estimated up front, from the tile it will decode. */
    val estimatedBytes: Long = 0L,
) {
    private val mutex = Mutex()
    private val lines = HashMap<Tile.Layer, Lines>()
    private val fills = HashMap<Tile.Layer, Fills>()

    /**
     * Every feature of a tile layer as `x, y` interleaved polylines, indexed like `layer.features`:
     * a line feature's parts, or a polygon feature's rings, empty for a point.
     */
    class Lines(val polylines: Array<List<FloatArray>>, internal val bounds: FloatArray)

    /** Every polygon feature of a tile layer as one fillable [Path], indexed like `layer.features`. */
    class Fills(val paths: Array<Path?>, internal val bounds: FloatArray)

    suspend fun lines(layer: Tile.Layer, extent: Int): Lines = mutex.withLock {
        lines.getOrPut(layer) {
            val bounds = emptyBounds(layer.features.size)
            val polylines = Array(layer.features.size) { index ->
                decodePolylines(layer.features[index], extent).also { parts ->
                    for (part in parts) extendBounds(bounds, index, part)
                }
            }
            Lines(polylines, bounds)
        }
    }

    suspend fun fills(layer: Tile.Layer, extent: Int): Fills = mutex.withLock {
        fills.getOrPut(layer) {
            val bounds = emptyBounds(layer.features.size)
            val paths = Array(layer.features.size) { index ->
                val feature = layer.features[index]
                if (feature.type != Tile.GeomType.POLYGON) return@Array null
                val rings = DECODERS.decodePolygonRingArraysUnclassified(
                    feature.geometry, extent = extent, canvasSize = geometrySize,
                )
                if (rings.isEmpty()) return@Array null
                val path = Path()
                path.fillType = PathFillType.NonZero
                for (ring in rings) {
                    if (ring.isEmpty()) continue
                    extendBounds(bounds, index, ring)
                    path.moveTo(ring[0], ring[1])
                    for (i in 1 until ring.size / 2) path.lineTo(ring[2 * i], ring[2 * i + 1])
                    path.close()
                }
                path
            }
            Fills(paths, bounds)
        }
    }

    private fun decodePolylines(feature: Tile.Feature, extent: Int): List<FloatArray> {
        val lines = when (feature.type) {
            Tile.GeomType.LINESTRING ->
                DECODERS.decodeLineArrays(feature.geometry, extent = extent, canvasSize = geometrySize)
            Tile.GeomType.POLYGON ->
                DECODERS.decodePolygonRingArrays(feature.geometry, extent = extent, canvasSize = geometrySize)
            else -> return emptyList()
        }
        // A part of fewer than two points has no segment to draw.
        return if (lines.all { it.size >= 4 }) lines else lines.filter { it.size >= 4 }
    }

    private fun emptyBounds(count: Int) = FloatArray(count * 4).also { bounds ->
        for (i in 0 until count) {
            bounds[4 * i] = Float.POSITIVE_INFINITY
            bounds[4 * i + 1] = Float.POSITIVE_INFINITY
            bounds[4 * i + 2] = Float.NEGATIVE_INFINITY
            bounds[4 * i + 3] = Float.NEGATIVE_INFINITY
        }
    }

    private fun extendBounds(bounds: FloatArray, index: Int, points: FloatArray) {
        var minX = bounds[4 * index]
        var minY = bounds[4 * index + 1]
        var maxX = bounds[4 * index + 2]
        var maxY = bounds[4 * index + 3]
        for (i in 0 until points.size / 2) {
            val x = points[2 * i]
            val y = points[2 * i + 1]
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
        }
        bounds[4 * index] = minX
        bounds[4 * index + 1] = minY
        bounds[4 * index + 2] = maxX
        bounds[4 * index + 3] = maxY
    }

    private companion object {
        val DECODERS = GeometryDecoders()
    }
}

/**
 * Whether a feature can reach the part of the canvas being drawn: [window] in geometry space -- the
 * tile bitmap, moved by the sub-square an overzoomed tile is -- against the feature's bounding box
 * grown by how far its paint reaches past its geometry.
 *
 * Nothing upstream needs this: a GPU discards what falls outside the viewport for free. Here an
 * overzoomed map tile is drawn from its whole ancestor, so at a span of 8 every one of 64 map tiles
 * used to tessellate and rasterize every road of the ancestor, 63 of them for nothing. Culling only
 * ever skips a feature whose every pixel would have been clipped, so it changes no pixel.
 */
class FeatureCull(
    private val windowLeft: Float,
    private val windowTop: Float,
    private val windowRight: Float,
    private val windowBottom: Float,
) {
    private var bounds: FloatArray? = null
    private var index = 0

    /** Points the cull at feature [index] of a layer whose boxes are [bounds]. */
    internal fun select(bounds: FloatArray, index: Int): FeatureCull {
        this.bounds = bounds
        this.index = index
        return this
    }

    /** Whether the selected feature, grown by [reach] canvas pixels on every side, touches the window. */
    fun reaches(reach: Float): Boolean {
        val box = bounds ?: return true
        val minX = box[4 * index]
        if (minX == Float.POSITIVE_INFINITY) return false
        return minX - reach < windowRight &&
            box[4 * index + 2] + reach > windowLeft &&
            box[4 * index + 1] - reach < windowBottom &&
            box[4 * index + 3] + reach > windowTop
    }
}
