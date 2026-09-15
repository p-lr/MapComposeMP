package ovh.plrapps.mapcompose.vector.data.glyphs

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns a glyph's coverage into a signed distance field. A port of `mapbox/tiny-sdf`, which is what
 * upstream rasterizes a locally drawn glyph with (`src/render/glyph_manager.ts`).
 *
 * Only the second half of upstream's `draw()` is here. The first half measures and draws the
 * character on a canvas; drawing is Compose's job in this port -- see `LocalGlyphRasterizer` -- and
 * what is handed over is the alpha of the glyph's ink box, which is exactly what upstream's
 * `getImageData(buffer, buffer, glyphWidth, glyphHeight)` returns.
 *
 * The maths is Felzenszwalb & Huttenlocher's squared distance transform, run once over the outside
 * of the shape and once over its inside, with a partially covered pixel seeded by a gamma-corrected
 * signed distance rather than by a hard in/out.
 */
internal object TinySdf {

    /** Upstream's `INF`. */
    private const val INF = 1e20

    /** Lookup table for gamma-corrected, signed squared alpha distance values. */
    private val alphaTable = DoubleArray(256) { index ->
        val d = 0.5 - (index / 255.0).pow(1.0 / 2.2)
        d * abs(d)
    }.also { it[255] = -INF }

    /**
     * The distance field of a glyph whose ink box is [glyphWidth] x [glyphHeight] and whose coverage
     * is [alpha], as bytes in the layout [Glyph.bitmap] carries.
     *
     * The result is `[glyphWidth] + 2 * [buffer]` wide and `[glyphHeight] + 2 * [buffer]` tall: the
     * field reaches past the ink on every side, which is what gives a halo something to dilate into.
     *
     * @param alpha row-major coverage of the ink box, `0..255`, `glyphWidth * glyphHeight` entries
     * @param radius how many pixels either side of the edge are encoded as distances
     * @param cutoff how much of the byte range stands for the inside of the shape
     */
    fun render(
        alpha: IntArray,
        glyphWidth: Int,
        glyphHeight: Int,
        buffer: Int,
        radius: Double,
        cutoff: Double,
    ): ByteArray {
        val width = glyphWidth + 2 * buffer
        val height = glyphHeight + 2 * buffer
        val length = max(width * height, 0)
        val data = ByteArray(length)
        if (glyphWidth <= 0 || glyphHeight <= 0) return data

        // Outside the glyph is an infinite distance for the outer grid, zero for the inner one.
        val gridOuter = DoubleArray(length) { INF }
        val gridInner = DoubleArray(length)

        /* A partially covered pixel is a distance approximation: a fully covered one is 0 outer and
         * INF inner, a partial one a small non-zero distance either side of 0.5 coverage. */
        for (y in 0 until glyphHeight) {
            var j = (y + buffer) * width + buffer
            for (x in 0 until glyphWidth) {
                val a = alpha[y * glyphWidth + x]
                if (a != 0) {
                    val t = alphaTable[a.coerceIn(0, 255)]
                    gridOuter[j] = max(0.0, t)
                    gridInner[j] = max(0.0, -t)
                }
                j++
            }
        }

        val scratch = Scratch(max(width, height))
        edt(gridOuter, 0, 0, width, height, width, scratch)
        /* The inner region is padded by a pixel so ink touching the box edge can see the seeds in
         * the buffer region, clamped so a zero buffer cannot underflow. */
        val pad = min(buffer, 1)
        edt(
            gridInner,
            buffer - pad,
            buffer - pad,
            glyphWidth + 2 * pad,
            glyphHeight + 2 * pad,
            width,
            scratch,
        )

        /* The signed distance as a byte: inside the glyph is high, outside is low, and the edge
         * gradient spans [-radius * cutoff, radius * (1 - cutoff)] pixels around the edge. */
        val scale = 255.0 / radius
        val base = 255.0 * (1.0 - cutoff)
        for (index in 0 until length) {
            val d = sqrt(gridOuter[index]) - sqrt(gridInner[index])
            data[index] = (base - scale * d).roundToInt().coerceIn(0, 255).toByte()
        }
        return data
    }

    /** Upstream's `f`, `v` and `z`, allocated once per call rather than once per rasterizer. */
    private class Scratch(size: Int) {
        val f = DoubleArray(size)
        val v = IntArray(size)
        val z = DoubleArray(size + 1)
    }

    /** 2D Euclidean squared distance transform, upstream's `edt`. */
    private fun edt(
        data: DoubleArray,
        x0: Int,
        y0: Int,
        width: Int,
        height: Int,
        gridSize: Int,
        scratch: Scratch,
    ) {
        for (x in x0 until x0 + width) {
            edt1d(data, y0 * gridSize + x, gridSize, height, scratch)
        }
        for (y in y0 until y0 + height) {
            edt1d(data, y * gridSize + x0, 1, width, scratch)
        }
    }

    /** 1D squared distance transform, upstream's `edt1d`. */
    private fun edt1d(grid: DoubleArray, offset: Int, stride: Int, length: Int, scratch: Scratch) {
        val f = scratch.f
        val v = scratch.v
        val z = scratch.z
        v[0] = 0
        z[0] = -INF
        z[1] = INF
        f[0] = grid[offset]

        var k = 0
        var s: Double
        for (q in 1 until length) {
            f[q] = grid[offset + q * stride]
            val q2 = (q * q).toDouble()
            do {
                val r = v[k]
                s = (f[q] - f[r] + q2 - (r * r).toDouble()) / (q - r) / 2.0
                if (s > z[k]) break
                k--
            } while (k > -1)

            k++
            v[k] = q
            z[k] = s
            z[k + 1] = INF
        }

        k = 0
        for (q in 0 until length) {
            while (z[k + 1] < q) k++
            val r = v[k]
            val qr = q - r
            grid[offset + q * stride] = f[r] + (qr * qr).toDouble()
        }
    }
}
