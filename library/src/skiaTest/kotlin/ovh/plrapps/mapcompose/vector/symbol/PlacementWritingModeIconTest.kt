package ovh.plrapps.mapcompose.vector.symbol

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import ovh.plrapps.mapcompose.vector.renderer.Point
import ovh.plrapps.mapcompose.vector.spec.sprites.Sprite as SpriteInfo
import ovh.plrapps.mapcompose.vector.spec.style.WRITING_MODE_HORIZONTAL
import ovh.plrapps.mapcompose.vector.spec.style.WRITING_MODE_VERTICAL
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The writing-mode walk on an icon with a label, where it wraps a `text-variable-anchor` loop.
 *
 * Here rather than in `commonTest` because a [SymbolInstance.SpriteWithText] carries an
 * [ImageBitmap], which `androidHostTest` cannot allocate -- the same reason
 * [PlacementCarryOverTest] lives in this source set.
 */
class PlacementWritingModeIconTest {

    private val viewport = SymbolFixtures.viewport()

    /**
     * An icon with a label offered at two anchors in each of its two settings.
     *
     * The world is 1000 px wide, so a normalized delta of `d` is `d * 1000` screen pixels. Anchor A
     * sits 30 px above the icon, anchor B 60 px to its right; the flat label is 40 x 12 and the
     * stacked one 12 x 40.
     */
    private fun spriteWithText(writingModes: List<String>): SymbolInstance.SpriteWithText {
        val anchor = Offset(LAYOUT_TILE_SIZE / 2f, LAYOUT_TILE_SIZE / 2f)
        val spritePlacement = SymbolFixtures.labelPlacement(text = "poi", center = anchor)

        fun candidate(dx: Float, dy: Float, width: Float, height: Float) = TextPlacementCandidate(
            labelPlacement = SymbolFixtures.labelPlacement(
                text = "poi", center = Offset(anchor.x + dx, anchor.y + dy),
                width = width, height = height,
            ),
            mercatorX = 0.5 + dx / 1000.0,
            mercatorY = 0.5 + dy / 1000.0,
            dx = dx,
            dy = dy,
        )

        return SymbolInstance.SpriteWithText(
            id = "poi",
            key = "poi",
            global = Point(0.5, 0.5),
            tileAnchor = anchor,
            placement = CompoundLabelPlacement(spritePlacement, spritePlacement),
            align = SymbolInstance.IN_CENTER,
            sprite = ImageBitmap(2, 2),
            spriteMeta = SpriteInfo(width = 2, height = 2, x = 0, y = 0),
            text = SymbolFixtures.FakeLabel("poi", 40f, 12f),
            spriteSize = IntSize(10, 10),
            textSize = IntSize(40, 12),
            textOffset = Offset(0f, -30f),
            iconOptional = false,
            textOptional = false,
            textCandidates = listOf(
                candidate(0f, -30f, 40f, 12f),
                candidate(60f, 0f, 40f, 12f),
            ),
            layoutSize = 16f,
            iconLayoutSize = 1f,
            verticalText = SymbolFixtures.FakeLabel("poi", 12f, 40f),
            verticalTextCandidates = listOf(
                candidate(0f, -30f, 12f, 40f),
                candidate(60f, 0f, 12f, 40f),
            ),
            writingModes = writingModes,
        ).also { it.crossTileID = 1L }
    }

    /**
     * An 8 x 8 box on the right-hand end of the flat label at anchor A, clear of the stacked box
     * there (which is only 12 wide) and clear of anchor B entirely.
     */
    private fun blocker(): SymbolInstance.Text = SymbolFixtures.textInstance(
        key = "blocker",
        global = Point(0.5 + 16.0 / 1000.0, 0.5 - 30.0 / 1000.0),
        width = 8f,
        height = 8f,
        layerIndex = 1,
    ).also { it.crossTileID = 2L }

    private fun placedLabel(writingModes: List<String>): SymbolInstance.Text {
        val symbol = spriteWithText(writingModes)
        val buckets = listOf(
            SymbolFixtures.bucket(listOf(symbol)),
            SymbolFixtures.bucket(listOf(blocker()), layerIndex = 1),
        )
        val placement = Placement(viewport, zoom = 6.0, collisionDetectionEnabled = true)
        placement.placeBuckets(PlacementOrder(buckets, viewport), previous = null)
        placement.commit(previous = null, now = 0L)
        return placement.result().symbols
            .map { it.instance }
            .filterIsInstance<SymbolInstance.Text>()
            .single { it.key == "poi" }
    }

    @Test
    fun `the mode walk wraps the anchor loop rather than the other way round`() {
        /* Upstream's `placeTextForPlacementModes` tries **every** anchor of the first-choice setting
         * before it considers the second. Anchor A's flat box is blocked and its stacked box is not,
         * so an anchor-outer walk would settle for the stacked setting there; the mode-outer walk
         * moves on to anchor B and keeps the flat setting the style asked for first. */
        val label = placedLabel(listOf(WRITING_MODE_HORIZONTAL, WRITING_MODE_VERTICAL))

        assertEquals(40f to 12f, label.value.width to label.value.height, "the flat setting")
        assertEquals(Offset(60f, 0f), label.textOffset, "at the second anchor")
    }

    @Test
    fun `vertical first takes the stacked setting at the first anchor that fits`() {
        val label = placedLabel(listOf(WRITING_MODE_VERTICAL, WRITING_MODE_HORIZONTAL))

        assertEquals(12f to 40f, label.value.width to label.value.height, "the stacked setting")
        assertEquals(Offset(0f, -30f), label.textOffset, "at the first anchor")
    }
}
