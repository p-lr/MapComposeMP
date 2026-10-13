package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Path
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ovh.plrapps.mapcompose.vector.spec.Tile

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
 */
class TileGeometry(
    val geometrySize: Int,
    /** What a cache should weigh this at: estimated up front, from the tile it will decode. */
    val estimatedBytes: Long = 0L,
) {
    private val mutex = Mutex()
    private val polylines = HashMap<Tile.Layer, Array<List<FloatArray>>>()
    private val fills = HashMap<Tile.Layer, Array<Path?>>()

    /**
     * Every feature of [layer] as `x, y` interleaved polylines, indexed like `layer.features`: a line
     * feature's parts, or a polygon feature's rings. Empty for a point.
     */
    suspend fun polylines(layer: Tile.Layer, extent: Int): Array<List<FloatArray>> = mutex.withLock {
        polylines.getOrPut(layer) {
            Array(layer.features.size) { index -> decodePolylines(layer.features[index], extent) }
        }
    }

    /** Every polygon feature of [layer] as one fillable [Path], indexed like `layer.features`. */
    suspend fun fillPaths(layer: Tile.Layer, extent: Int): Array<Path?> = mutex.withLock {
        fills.getOrPut(layer) {
            Array(layer.features.size) { index ->
                val feature = layer.features[index]
                if (feature.type == Tile.GeomType.POLYGON) {
                    DECODERS.decodePolygonPath(feature.geometry, extent = extent, canvasSize = geometrySize)
                } else {
                    null
                }
            }
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

    private companion object {
        val DECODERS = GeometryDecoders()
    }
}
