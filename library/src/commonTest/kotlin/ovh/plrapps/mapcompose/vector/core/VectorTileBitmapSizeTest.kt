package ovh.plrapps.mapcompose.vector.core

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the invariant behind [vectorTileBitmapSize]: the rasterized bitmap is never smaller than the
 * area the tile is drawn into.
 *
 * A tile covers `mapState.tileSize * relativeScale` device pixels, with `relativeScale` in
 * `(2^(magnifyingFactor - 1), 2^magnifyingFactor]`, so the bitmap has to be at least
 * `tileSize * 2^magnifyingFactor` to be minified rather than stretched. The size used to be a
 * hardcoded 256 regardless of `tileSize`, which upscaled every tile by up to 2x on a map built with
 * 512 px tiles.
 */
class VectorTileBitmapSizeTest {

    @Test
    fun `the bitmap matches the map's tile size`() {
        assertEquals(256, size(tileSize = 256))
        assertEquals(512, size(tileSize = 512))
    }

    @Test
    fun `density only adds resolution`() {
        assertEquals(512, size(tileSize = 256, density = 2f))
        assertEquals(768, size(tileSize = 256, density = 3f))
        assertEquals(1024, size(tileSize = 512, density = 2f))
    }

    @Test
    fun `super sampling multiplies on top of density`() {
        assertEquals(1024, size(tileSize = 512, superSampling = 2))
        assertEquals(2048, size(tileSize = 512, density = 2f, superSampling = 2))
    }

    @Test
    fun `a factor below one is treated as one`() {
        assertEquals(512, size(tileSize = 512, superSampling = 0))
        assertEquals(512, size(tileSize = 512, superSampling = -3))
        assertEquals(512, size(tileSize = 512, magnifyingFactor = -1))
    }

    @Test
    fun `a density below one never shrinks the bitmap below the tile size`() {
        // A fractional density would otherwise rasterize below the size the tile is drawn at.
        assertEquals(512, size(tileSize = 512, density = 0.75f))
    }

    @Test
    fun `a magnifying factor raises the resolution on its own`() {
        // A density-1 screen with a magnifying factor still draws each tile twice as large.
        assertEquals(1024, size(tileSize = 512, density = 1f, magnifyingFactor = 1))
        assertEquals(2048, size(tileSize = 512, density = 1f, magnifyingFactor = 2))
    }

    @Test
    fun `density and the magnifying factor do not compound`() {
        // Both describe the same destination size, so the larger wins rather than their product --
        // multiplying them would rasterize four times the pixels a 2x screen actually draws.
        assertEquals(1024, size(tileSize = 512, density = 2f, magnifyingFactor = 1))
        assertEquals(2048, size(tileSize = 512, density = 3f, magnifyingFactor = 2))
    }

    @Test
    fun `the bitmap always covers the largest area a tile is drawn into`() {
        for (tileSize in listOf(128, 256, 512, 1024)) {
            for (density in listOf(0.75f, 1f, 1.5f, 2f, 3f, 4f)) {
                for (superSampling in listOf(1, 2)) {
                    for (magnifyingFactor in 0..3) {
                        val bitmap = size(tileSize, density, superSampling, magnifyingFactor)
                        val destination = tileSize * 2f.pow(magnifyingFactor)
                        assertTrue(
                            bitmap >= destination,
                            "tileSize=$tileSize density=$density ss=$superSampling " +
                                "mf=$magnifyingFactor gave $bitmap for a destination of $destination"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the magnifying factor puts a style pixel on a density-independent pixel`() {
        assertEquals(0, magnifyingFactorForDensity(1f))
        assertEquals(1, magnifyingFactorForDensity(2f))
        assertEquals(2, magnifyingFactorForDensity(3f))
        assertEquals(2, magnifyingFactorForDensity(4f))
    }

    @Test
    fun `a density below one asks for no magnification`() {
        // log2 of a fraction is negative; the level can never be shifted the wrong way.
        assertEquals(0, magnifyingFactorForDensity(0.75f))
        assertEquals(0, magnifyingFactorForDensity(0f))
    }

    private fun size(
        tileSize: Int,
        density: Float = 1f,
        superSampling: Int = 1,
        magnifyingFactor: Int = 0,
    ): Int = vectorTileBitmapSize(tileSize, density, superSampling, magnifyingFactor)
}
