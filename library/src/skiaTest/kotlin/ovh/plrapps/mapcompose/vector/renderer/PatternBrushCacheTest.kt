package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import ovh.plrapps.mapcompose.vector.data.SpriteManager
import ovh.plrapps.mapcompose.vector.renderer.utils.PatternBrushCache
import ovh.plrapps.mapcompose.vector.renderer.utils.patternTile
import ovh.plrapps.mapcompose.vector.spec.sprites.Sprite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * `fill-pattern` / `line-pattern` / `background-pattern` scaling and world anchoring.
 *
 * A Compose shader has no local matrix, so both live in the bitmap the brush repeats; these check
 * that what comes out is the size and the phase upstream's `u_scale` and `u_pixel_coord_*` give.
 */
class PatternBrushCacheTest {

    private val stripes = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow)

    /** A four-pixel-wide sprite with one solid colour per column. */
    private fun stripeSprite(size: Int = 4): ImageBitmap = renderToBitmap(size = size) {
        for (x in 0 until size) {
            drawRect(
                color = stripes[x % stripes.size],
                topLeft = Offset(x.toFloat(), 0f),
                size = Size(1f, size.toFloat()),
            )
        }
    }

    private fun spriteManager(
        image: ImageBitmap,
        pixelRatio: Float = 1f,
        sdf: Boolean = false,
    ): SpriteManager = SpriteManager(
        spriteIndex = mapOf(
            "stripes" to Sprite(
                width = image.width, height = image.height, x = 0, y = 0,
                pixelRatio = pixelRatio, sdf = sdf,
            )
        ),
        spriteImage = image,
    )

    /** Fills a `width` x 1 strip with [cache]'s brush and reads the row back. */
    private fun rowOf(cache: PatternBrushCache, sprites: SpriteManager, width: Int): List<Color> {
        val brush = assertNotNull(cache.get(sprites, "stripes"))
        val bitmap = renderToBitmap(size = width) { drawRect(brush = brush) }
        return (0 until width).map { bitmap.pixelAt(it, 0) }
    }

    @Test
    fun `a pattern repeats its sprite`() {
        val sprites = spriteManager(stripeSprite())
        val cache = PatternBrushCache().apply {
            configure(tileX = 0, tileY = 0, canvasSize = 8, tileZoom = 10.0, actualZoom = 10.0, density = 1f)
        }
        val row = rowOf(cache, sprites, 8)
        for (x in 0 until 8) assertColorEquals(stripes[x % 4], row[x], message = "x=$x")
    }

    @Test
    fun `the pattern is continuous across a tile boundary`() {
        // A 4 px pattern in an 8 px tile divides evenly, so use a 6 px tile: without the world
        // anchoring the second tile would restart the pattern at its own corner.
        val sprites = spriteManager(stripeSprite())
        val first = PatternBrushCache().apply {
            configure(tileX = 0, tileY = 0, canvasSize = 6, tileZoom = 10.0, actualZoom = 10.0, density = 1f)
        }
        val second = PatternBrushCache().apply {
            configure(tileX = 1, tileY = 0, canvasSize = 6, tileZoom = 10.0, actualZoom = 10.0, density = 1f)
        }
        val world = rowOf(first, sprites, 6) + rowOf(second, sprites, 6)
        for (x in 0 until 12) {
            assertColorEquals(stripes[x % 4], world[x], message = "world x=$x")
        }
    }

    @Test
    fun `a hidpi sprite tiles at its layout size`() {
        // Eight sheet pixels at pixelRatio 2 are four layout pixels, so each colour is one pixel
        // wide, not two. Without dividing the ratio out an @2x sheet tiled twice too coarsely.
        val sheet = renderToBitmap(size = 8) {
            for (x in 0 until 8) {
                drawRect(
                    color = stripes[(x / 2) % stripes.size],
                    topLeft = Offset(x.toFloat(), 0f),
                    size = Size(1f, 8f),
                )
            }
        }
        val sprites = spriteManager(sheet, pixelRatio = 2f)
        val cache = PatternBrushCache().apply {
            configure(tileX = 0, tileY = 0, canvasSize = 8, tileZoom = 10.0, actualZoom = 10.0, density = 1f)
        }
        val row = rowOf(cache, sprites, 8)
        for (x in 0 until 8) assertColorEquals(stripes[x % 4], row[x], message = "x=$x")
    }

    /**
     * The period of the repeat, in pixels.
     *
     * A magnified pattern is filtered, as upstream's `LINEAR` texture is, so the colours between
     * two source pixels are blended and cannot be asserted exactly. The *period* is what the scale
     * actually changes, and it survives the filtering.
     */
    private fun periodOf(row: List<Color>): Int {
        for (period in 1..row.size / 2) {
            if ((0 until row.size - period).all { colorsMatch(row[it], row[it + period]) }) return period
        }
        return row.size
    }

    private fun colorsMatch(a: Color, b: Color): Boolean =
        kotlin.math.abs(a.red - b.red) < 0.02f &&
            kotlin.math.abs(a.green - b.green) < 0.02f &&
            kotlin.math.abs(a.blue - b.blue) < 0.02f

    @Test
    fun `density scales the pattern up`() {
        val sprites = spriteManager(stripeSprite())
        val plain = PatternBrushCache().apply {
            configure(tileX = 0, tileY = 0, canvasSize = 16, tileZoom = 10.0, actualZoom = 10.0, density = 1f)
        }
        val dense = PatternBrushCache().apply {
            configure(tileX = 0, tileY = 0, canvasSize = 16, tileZoom = 10.0, actualZoom = 10.0, density = 2f)
        }
        assertEquals(4, periodOf(rowOf(plain, sprites, 16)))
        assertEquals(8, periodOf(rowOf(dense, sprites, 16)))
    }

    @Test
    fun `a shrunk tile draws the pattern larger`() {
        // The tile is rasterized for zoom 11 but the map is at 10, so it will be drawn at half its
        // bitmap size -- the pattern is doubled in tile space to come out the same on screen.
        val sprites = spriteManager(stripeSprite())
        val cache = PatternBrushCache().apply {
            configure(tileX = 0, tileY = 0, canvasSize = 16, tileZoom = 11.0, actualZoom = 10.0, density = 1f)
        }
        assertEquals(8, periodOf(rowOf(cache, sprites, 16)))
    }

    @Test
    fun `an sdf sprite is not a pattern`() {
        val sprites = spriteManager(stripeSprite(), sdf = true)
        val cache = PatternBrushCache().apply {
            configure(tileX = 0, tileY = 0, canvasSize = 8, tileZoom = 10.0, actualZoom = 10.0, density = 1f)
        }
        assertNull(cache.get(sprites, "stripes"))
    }

    @Test
    fun `an unknown pattern name resolves to nothing`() {
        val sprites = spriteManager(stripeSprite())
        val cache = PatternBrushCache()
        assertNull(cache.get(sprites, "missing"))
        assertNull(cache.get(sprites, null))
        assertNull(cache.get(null, "stripes"))
    }

    @Test
    fun `rolling a pattern tile shifts its phase`() {
        val rolled = patternTile(stripeSprite(), width = 4, height = 4, phaseX = 1, phaseY = 0)
        for (x in 0 until 4) {
            assertColorEquals(stripes[(x + 1) % 4], rolled.pixelAt(x, 0), message = "x=$x")
        }
    }

    @Test
    fun `a re-configure with the same scale and phase keeps the built brushes`() {
        // `TileRenderer` configures the cache before every style layer, not once per tile, and most
        // of a style's layers are drawn in the same space. Dropping the entries each time would
        // rebuild the pattern bitmap -- an allocation and four draws -- for every one of them.
        val sprites = spriteManager(stripeSprite())
        val cache = PatternBrushCache()

        cache.configure(tileX = 2, tileY = 3, canvasSize = 8, tileZoom = 10.0, actualZoom = 10.0, density = 1f)
        val first = assertNotNull(cache.get(sprites, "stripes"))

        cache.configure(tileX = 2, tileY = 3, canvasSize = 8, tileZoom = 10.0, actualZoom = 10.0, density = 1f)
        assertSame(first, cache.get(sprites, "stripes"))
    }

    @Test
    fun `a re-configure with a different phase drops the built brushes`() {
        val sprites = spriteManager(stripeSprite())
        val cache = PatternBrushCache()

        cache.configure(tileX = 2, tileY = 3, canvasSize = 6, tileZoom = 10.0, actualZoom = 10.0, density = 1f)
        val first = assertNotNull(cache.get(sprites, "stripes"))

        cache.configure(tileX = 3, tileY = 3, canvasSize = 6, tileZoom = 10.0, actualZoom = 10.0, density = 1f)
        assertNotSame(first, cache.get(sprites, "stripes"))
    }

    @Test
    fun `a re-configure with a different scale drops the built brushes`() {
        val sprites = spriteManager(stripeSprite())
        val cache = PatternBrushCache()

        cache.configure(tileX = 0, tileY = 0, canvasSize = 8, tileZoom = 10.0, actualZoom = 10.0, density = 1f)
        val first = assertNotNull(cache.get(sprites, "stripes"))

        cache.configure(tileX = 0, tileY = 0, canvasSize = 8, tileZoom = 10.0, actualZoom = 10.0, density = 2f)
        assertNotSame(first, cache.get(sprites, "stripes"))
    }

    @Test
    fun `the first configure always takes effect`() {
        // The fields start at a reachable configuration -- scale 1, phase 0 -- so "unchanged" must
        // not be inferred from them, or a cache configured with exactly those would never be armed.
        val sprites = spriteManager(stripeSprite())
        val cache = PatternBrushCache()
        cache.configure(tileX = 0, tileY = 0, canvasSize = 8, tileZoom = 10.0, actualZoom = 10.0, density = 1f)

        val row = rowOf(cache, sprites, 8)
        for (x in 0 until 8) assertColorEquals(stripes[x % 4], row[x], message = "x=$x")
    }

    @Test
    fun `a zero phase leaves the pattern alone`() {
        val rolled = patternTile(stripeSprite(), width = 4, height = 4, phaseX = 0, phaseY = 0)
        for (x in 0 until 4) {
            assertColorEquals(stripes[x], rolled.pixelAt(x, 0), message = "x=$x")
        }
    }
}
