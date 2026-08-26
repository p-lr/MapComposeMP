package ovh.plrapps.mapcompose.vector.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap

/**
 * One decoded elevation tile: `dim x dim` metres-above-sea-level samples, plus a 1 px border ring.
 *
 * A port of `DEMData` in `maplibre-gl/src/data/dem_data.ts`, minus the worker-transfer plumbing.
 * The border is what makes the Sobel operator in
 * [ovh.plrapps.mapcompose.vector.renderer.utils.sobelDeriv] well defined at the tile's edge: it is
 * seeded by clamping to the nearest interior sample and then replaced with the neighbouring tile's
 * real data by [backfillBorder], exactly as upstream does. Without that replacement the derivative
 * at an edge is halved and a grid of seams shows across the map.
 *
 * Upstream stores the raw RGBA and unpacks in the shader; here the unpack happens once, at decode,
 * because the shading runs on the CPU anyway.
 */
class DemData internal constructor(
    val dim: Int,
    private val data: FloatArray,
) {
    private val stride = dim + 2

    /**
     * The elevation in metres at [x], [y], each valid over `-1..dim` -- the interior plus the
     * border ring.
     */
    operator fun get(x: Int, y: Int): Float {
        require(x in -1..dim && y in -1..dim) {
            "out of range source coordinates for DEM data: ($x, $y) with dim $dim"
        }
        return data[idx(x, y)]
    }

    private fun idx(x: Int, y: Int): Int = (y + 1) * stride + (x + 1)

    /**
     * Replaces the part of this tile's border ring that [neighbour] covers.
     *
     * [dx] and [dy] are the neighbour's offset in tile coordinates, each in `-1..1`. A port of
     * `DEMData#backfillBorder`.
     */
    fun backfillBorder(neighbour: DemData, dx: Int, dy: Int) {
        require(dim == neighbour.dim) { "dem dimension mismatch" }

        var xMin = dx * dim
        var xMax = dx * dim + dim
        var yMin = dy * dim
        var yMax = dy * dim + dim

        when (dx) {
            -1 -> xMin = xMax - 1
            1 -> xMax = xMin + 1
        }
        when (dy) {
            -1 -> yMin = yMax - 1
            1 -> yMax = yMin + 1
        }

        val ox = -dx * dim
        val oy = -dy * dim

        for (y in yMin until yMax) {
            for (x in xMin until xMax) {
                data[idx(x, y)] = neighbour.data[neighbour.idx(x + ox, y + oy)]
            }
        }
    }

    companion object {
        /**
         * Decodes a square ARGB tile into elevations, with the border ring seeded by clamping.
         *
         * Takes an `IntArray` rather than an `ImageBitmap` so that the whole decode is testable
         * where `ImageBitmap` cannot be allocated -- see [ofImage] for the adapter. Returns `null`
         * for a non-square or empty tile; upstream throws, but one malformed tile should leave a
         * hole here rather than fail the whole rasterization.
         */
        fun fromArgb(argb: IntArray, width: Int, height: Int, unpack: DemUnpack): DemData? {
            if (width <= 0 || width != height || argb.size < width * height) return null

            val dim = width
            val stride = dim + 2
            val data = FloatArray(stride * stride)
            fun idx(x: Int, y: Int) = (y + 1) * stride + (x + 1)

            for (y in 0 until dim) {
                for (x in 0 until dim) {
                    val pixel = argb[y * dim + x]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    data[idx(x, y)] = unpack.elevationOf(r, g, b).toFloat()
                }
            }

            /* Seed the border with the nearest interior sample, "in order to avoid flashing seams
             * between tiles" as upstream puts it. backfillBorder overwrites this once a neighbour
             * is available; a neighbour that never loads keeps the clamp. */
            for (i in 0 until dim) {
                data[idx(-1, i)] = data[idx(0, i)]
                data[idx(dim, i)] = data[idx(dim - 1, i)]
                data[idx(i, -1)] = data[idx(i, 0)]
                data[idx(i, dim)] = data[idx(i, dim - 1)]
            }
            data[idx(-1, -1)] = data[idx(0, 0)]
            data[idx(dim, -1)] = data[idx(dim - 1, 0)]
            data[idx(-1, dim)] = data[idx(0, dim - 1)]
            data[idx(dim, dim)] = data[idx(dim - 1, dim - 1)]

            return DemData(dim, data)
        }

        /**
         * [fromArgb] over a decoded image tile.
         *
         * Reads `PixelMap.buffer` directly rather than `PixelMap[x, y]`: a DEM channel is an exact
         * 8-bit integer and must not make a round trip through `Color`'s floats.
         */
        fun ofImage(image: ImageBitmap, unpack: DemUnpack): DemData? {
            val pixels = image.toPixelMap()
            if (pixels.stride != pixels.width || pixels.bufferOffset != 0) return null
            return fromArgb(pixels.buffer, pixels.width, pixels.height, unpack)
        }
    }
}
