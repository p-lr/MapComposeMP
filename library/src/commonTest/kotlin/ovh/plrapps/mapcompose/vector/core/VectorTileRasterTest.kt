package ovh.plrapps.mapcompose.vector.core

import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the policy behind [vectorTileRaster]: a tile is rasterized at the screen's density, never
 * stretched by more than `sqrt(2)` whatever magnifying factor the map uses, and never into a bitmap
 * larger than [MAX_RENDER_PX] -- super-sampling gives way first.
 *
 * A tile covers `mapState.tileSize * relativeScale` device pixels, with `relativeScale` in
 * `(2^(magnifyingFactor - 1), 2^magnifyingFactor]`. The size used to be `2^magnifyingFactor` times
 * the tile size times the super-sampling factor, uncapped, which on a 3x phone with 512 px tiles was
 * a 4096 px bitmap per tile.
 */
class VectorTileRasterTest {

    @Test
    fun `the bitmap matches the map's tile size`() {
        assertRaster(256, 1, raster(tileSize = 256))
        assertRaster(512, 1, raster(tileSize = 512))
    }

    @Test
    fun `density adds resolution`() {
        assertRaster(512, 1, raster(tileSize = 256, density = 2f))
        assertRaster(768, 1, raster(tileSize = 256, density = 3f))
        assertRaster(1024, 1, raster(tileSize = 512, density = 2f))
    }

    @Test
    fun `super sampling multiplies on top of density while it fits`() {
        assertRaster(1024, 2, raster(tileSize = 512, superSampling = 2))
        assertRaster(2048, 2, raster(tileSize = 512, density = 2f, superSampling = 2))
        assertRaster(1536, 2, raster(tileSize = 256, density = 3f, superSampling = 2))
    }

    @Test
    fun `super sampling gives way first when the render would exceed the cap`() {
        // 512 px tiles on a 3x screen: 1536 px, which super-sampled would be 3072.
        val result = raster(tileSize = 512, density = 3f, superSampling = 2)
        assertRaster(1536, 1, result)
        assertEquals(1536, result.outputPx)
    }

    @Test
    fun `the resolution itself is clamped only after super sampling is gone`() {
        assertRaster(MAX_RENDER_PX, 1, raster(tileSize = 1024, density = 3f, superSampling = 2))
    }

    @Test
    fun `a factor below one is treated as one`() {
        assertRaster(512, 1, raster(tileSize = 512, superSampling = 0))
        assertRaster(512, 1, raster(tileSize = 512, superSampling = -3))
        assertRaster(512, 1, raster(tileSize = 512, magnifyingFactor = -1))
    }

    @Test
    fun `a density below one never shrinks the bitmap below the tile size`() {
        // A fractional density would otherwise rasterize below the size the tile is drawn at.
        assertRaster(512, 1, raster(tileSize = 512, density = 0.75f))
    }

    @Test
    fun `a magnifying factor alone rasterizes at the middle of its level`() {
        // A density-1 screen with a magnifying factor draws each tile between 1x and 2x as large;
        // rasterizing at sqrt 2 stretches the top of the range and shrinks the bottom equally.
        assertRaster(724, 1, raster(tileSize = 512, density = 1f, magnifyingFactor = 1))
        assertRaster(1448, 1, raster(tileSize = 512, density = 1f, magnifyingFactor = 2))
    }

    @Test
    fun `the factor derived from the density rasterizes at the density`() {
        for (density in listOf(1f, 1.5f, 2f, 2.625f, 3f, 3.5f, 4f)) {
            val magnifyingFactor = magnifyingFactorForDensity(density)
            val result = raster(tileSize = 256, density = density, magnifyingFactor = magnifyingFactor)
            assertEquals((256 * density).toInt(), result.renderPx, "density $density")
        }
    }

    @Test
    fun `a tile is never stretched by more than sqrt 2 below the cap`() {
        for (tileSize in listOf(128, 256, 512)) {
            for (density in listOf(0.75f, 1f, 1.5f, 2f, 3f, 4f)) {
                for (superSampling in listOf(1, 2)) {
                    for (magnifyingFactor in 0..3) {
                        val result = raster(tileSize, density, superSampling, magnifyingFactor, Int.MAX_VALUE)
                        val destination = tileSize * 2f.pow(magnifyingFactor)
                        assertTrue(
                            result.outputPx * sqrt(2f) >= destination - 1f,
                            "tileSize=$tileSize density=$density ss=$superSampling " +
                                "mf=$magnifyingFactor gave ${result.outputPx} for a destination of $destination"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the render never exceeds the cap`() {
        for (tileSize in listOf(128, 256, 512, 1024)) {
            for (density in listOf(1f, 2f, 3f, 4f)) {
                for (superSampling in listOf(1, 2, 3, 4)) {
                    for (magnifyingFactor in 0..3) {
                        val result = raster(tileSize, density, superSampling, magnifyingFactor)
                        assertTrue(result.renderPx <= MAX_RENDER_PX, "got ${result.renderPx}")
                        assertEquals(0, result.renderPx % result.superSampling)
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

    private fun assertRaster(renderPx: Int, superSampling: Int, actual: VectorTileRaster) {
        assertEquals(renderPx, actual.renderPx, "renderPx")
        assertEquals(superSampling, actual.superSampling, "superSampling")
    }

    private fun raster(
        tileSize: Int,
        density: Float = 1f,
        superSampling: Int = 1,
        magnifyingFactor: Int = 0,
        maxRenderPx: Int = MAX_RENDER_PX,
    ): VectorTileRaster = vectorTileRaster(tileSize, density, superSampling, magnifyingFactor, maxRenderPx)
}
