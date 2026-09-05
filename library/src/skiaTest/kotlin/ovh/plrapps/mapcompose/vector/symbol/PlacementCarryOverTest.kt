package ovh.plrapps.mapcompose.vector.symbol

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import ovh.plrapps.mapcompose.vector.renderer.Point
import ovh.plrapps.mapcompose.vector.spec.sprites.Sprite as SpriteInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What [Placement.result] keeps drawing for a symbol that is no longer placed.
 *
 * Here rather than in `commonTest` because a [SymbolInstance.SpriteWithText] carries an
 * [ImageBitmap], which `androidHostTest` cannot allocate -- the same reason the painter tests and
 * `SymbolLayerLayoutTest` live in this source set.
 */
class PlacementCarryOverTest {

    private val viewport = SymbolFixtures.viewport()

    /** An icon with a label at a `text-variable-anchor` 20 px above it, both 10 x 10. */
    private fun spriteWithText(id: String, layerIndex: Int = 0): SymbolInstance.SpriteWithText {
        val anchor = Offset(LAYOUT_TILE_SIZE / 2f, LAYOUT_TILE_SIZE / 2f)
        val spritePlacement = SymbolFixtures.labelPlacement(
            text = id, center = anchor, layerIndex = layerIndex,
        )
        val textPlacement = SymbolFixtures.labelPlacement(
            text = id, center = anchor, layerIndex = layerIndex,
        )
        val instance = SymbolInstance.SpriteWithText(
            id = id,
            key = id,
            global = Point(0.5, 0.5),
            tileAnchor = anchor,
            placement = CompoundLabelPlacement(spritePlacement, textPlacement),
            align = SymbolInstance.IN_CENTER,
            sprite = ImageBitmap(2, 2),
            spriteMeta = SpriteInfo(width = 2, height = 2, x = 0, y = 0),
            text = SymbolFixtures.FakeLabel(id),
            spriteSize = IntSize(10, 10),
            textSize = IntSize(10, 10),
            textOffset = Offset(0f, -20f),
            iconOptional = false,
            textOptional = false,
            /* The whole point of the fixture: the label was placed at a chosen anchor, 20 screen
             * pixels above the icon, and not at the plain `text-offset`. */
            textCandidates = listOf(
                TextPlacementCandidate(
                    labelPlacement = textPlacement,
                    mercatorX = 0.5,
                    mercatorY = 0.5 - 20.0 / 1000.0,
                    dx = 0f,
                    dy = -20f,
                )
            ),
            layoutSize = 16f,
            iconLayoutSize = 1f,
        )
        instance.crossTileID = 1L
        return instance
    }

    private fun placed(buckets: List<SymbolBucket>, previous: Placement?, now: Long): Placement =
        Placement(viewportInfo = viewport, zoom = 6.0, collisionDetectionEnabled = true).also {
            it.placeBuckets(PlacementOrder(buckets), previous)
            it.commit(previous, now)
        }

    @Test
    fun `a symbol whose bucket left keeps both of its halves while it fades`() {
        val symbol = spriteWithText("poi")
        val bucket = SymbolFixtures.bucket(listOf(symbol))

        val first = placed(listOf(bucket), previous = null, now = 0L)
        val drawn = first.result(null)
        assertEquals(
            2, drawn.symbols.size,
            "a variable-anchored icon and its label are two entries under one crossTileID",
        )

        /* The tile departed: no buckets at all, which is what a pan or a zoom does to the set. */
        val second = placed(emptyList(), previous = first, now = SYMBOL_FADE_DURATION_MS / 2)
        val fading = second.result(first)

        assertEquals(2, fading.symbols.size, "both halves fade, rather than the icon vanishing")
        assertTrue(fading.symbols.any { it.instance is SymbolInstance.Sprite }, "the icon is carried")
        assertTrue(fading.symbols.any { it.instance is SymbolInstance.Text }, "the label is carried")
        assertTrue(
            fading.symbols.all { it.opacity.text.opacity > 0f || it.opacity.icon.opacity > 0f },
            "carried at a falling opacity, not at zero",
        )
    }

    @Test
    fun `a symbol that lost its ground fades from where it was drawn`() {
        val symbol = spriteWithText("poi")
        val bucket = SymbolFixtures.bucket(listOf(symbol))
        val first = placed(listOf(bucket), previous = null, now = 0L)
        /* Every cycle draws, so every previous placement has a result to carry over from. */
        first.result(null)

        /* A later style layer takes the ground: `PlacementOrder` places the higher `layerIndex`
         * first, so the icon collides and the whole symbol is rejected this cycle. */
        val blocker = SymbolFixtures.textInstance(
            key = "blocker", width = 100f, height = 100f, layerIndex = 1,
        )
        blocker.crossTileID = 2L
        val blockerBucket = SymbolFixtures.bucket(listOf(blocker), layerIndex = 1)

        val second = placed(
            listOf(bucket, blockerBucket), previous = first, now = SYMBOL_FADE_DURATION_MS / 2,
        )
        val fading = second.result(first)

        val carried = fading.symbols.filter { it.crossTileID == 1L }
        assertEquals(2, carried.size, "both halves keep fading")
        val label = carried.map { it.instance }.filterIsInstance<SymbolInstance.Text>().single()
        assertEquals(
            Offset(0f, -20f), label.textOffset,
            "the label fades from the anchor it settled on, not from the plain text-offset",
        )
    }
}
