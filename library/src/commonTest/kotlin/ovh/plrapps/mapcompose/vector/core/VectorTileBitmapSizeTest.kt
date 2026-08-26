package ovh.plrapps.mapcompose.vector.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the invariant behind [vectorTileBitmapSize]: the rasterized bitmap is never smaller than the
 * area the tile is drawn into.
 *
 * A tile covers `mapState.tileSize * relativeScale` device pixels, with `relativeScale` in
 * `(0.5, 1.0]`, so a bitmap of at least `tileSize` is always minified rather than stretched. This
 * used to be a hardcoded 256 regardless of `tileSize`, which upscaled every tile by up to 2x on a
 * map built with 512 px tiles.
 */
class VectorTileBitmapSizeTest {

    @Test
    fun `the bitmap matches the map's tile size`() {
        assertEquals(256, vectorTileBitmapSize(tileSize = 256, density = 1f, superSampling = 1))
        assertEquals(512, vectorTileBitmapSize(tileSize = 512, density = 1f, superSampling = 1))
    }

    @Test
    fun `density only adds resolution`() {
        assertEquals(512, vectorTileBitmapSize(tileSize = 256, density = 2f, superSampling = 1))
        assertEquals(768, vectorTileBitmapSize(tileSize = 256, density = 3f, superSampling = 1))
        assertEquals(1024, vectorTileBitmapSize(tileSize = 512, density = 2f, superSampling = 1))
    }

    @Test
    fun `super sampling multiplies on top of density`() {
        assertEquals(1024, vectorTileBitmapSize(tileSize = 512, density = 1f, superSampling = 2))
        assertEquals(2048, vectorTileBitmapSize(tileSize = 512, density = 2f, superSampling = 2))
    }

    @Test
    fun `a factor below one is treated as one`() {
        assertEquals(512, vectorTileBitmapSize(tileSize = 512, density = 1f, superSampling = 0))
        assertEquals(512, vectorTileBitmapSize(tileSize = 512, density = 1f, superSampling = -3))
    }

    @Test
    fun `a density below one never shrinks the bitmap below the tile size`() {
        // A fractional density would otherwise rasterize below the size the tile is drawn at.
        assertEquals(512, vectorTileBitmapSize(tileSize = 512, density = 0.75f, superSampling = 1))
    }

    @Test
    fun `the bitmap always covers the largest area a tile is drawn into`() {
        // relativeScale is in (0.5, 1.0], so the destination never exceeds tileSize device pixels.
        for (tileSize in listOf(128, 256, 512, 1024)) {
            for (density in listOf(1f, 1.5f, 2f, 3f)) {
                for (superSampling in listOf(1, 2)) {
                    val bitmap = vectorTileBitmapSize(tileSize, density, superSampling)
                    assertTrue(
                        bitmap >= tileSize,
                        "tileSize=$tileSize density=$density ss=$superSampling gave $bitmap"
                    )
                }
            }
        }
    }
}
