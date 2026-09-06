package ovh.plrapps.mapcompose.vector.symbol

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import ovh.plrapps.mapcompose.vector.renderer.LabelArt
import ovh.plrapps.mapcompose.vector.renderer.Point
import ovh.plrapps.mapcompose.vector.spec.sprites.Sprite as SpriteInfo
import ovh.plrapps.mapcompose.vector.utils.obb.ObbPoint
import kotlin.math.max
import kotlin.math.min

/** An icon's collision box and its label's, which are tested apart and admitted together. */
internal data class CompoundLabelPlacement(
    val spritePlacement: LabelPlacement,
    val textPlacement: LabelPlacement?,
)

/**
 * One `text-variable-anchor` option: where the label would sit if this anchor were chosen.
 *
 * The placement pass tries them in order and keeps the first that fits -- upstream's
 * `attemptAnchorPlacement` (`placement.ts`).
 */
internal data class TextPlacementCandidate(
    val labelPlacement: LabelPlacement,
    val mercatorX: Double,
    val mercatorY: Double,
    val dx: Float,
    val dy: Float,
)

/** Where one symbol of a feature is anchored within its tile, and at what angle. */
internal data class SymbolAnchorPlacement(
    val position: ObbPoint,
    val angle: Float,
)

/**
 * One laid-out symbol, ported from `SymbolInstance` in
 * `maplibre-gl-js/src/data/bucket/symbol_bucket.ts`.
 *
 * Everything here is **view-independent**: it is produced once per canonical tile per style layer,
 * at that bucket's integer zoom and against a fixed [LAYOUT_TILE_SIZE], and survives every pan,
 * rotation and fractional zoom until the tile itself changes. What varies with the view -- where the
 * anchor lands on screen, how large the symbol is drawn, whether it beat its neighbours to the
 * ground it wants -- belongs to [Placement].
 *
 * Three fields exist only for the passes downstream:
 *
 * - [key] is what [CrossTileSymbolIndex] matches on, so the same label in a parent and a child tile
 *   is recognised as one symbol and neither fades nor re-picks its anchor when the tiles swap.
 * - [crossTileID] is that identity once assigned; 0 means "not yet indexed".
 * - [featureSizes] together with the bucket's [SizeData] give the size at the drawn zoom, whose
 *   ratio to [layoutSize] is the scale the symbol is drawn and collided at.
 */
internal sealed class SymbolInstance(
    val id: String,
    val key: String,
    val global: Point,
    /** The anchor in the bucket's own layout space, which is what a circle chain is walked in. */
    val tileAnchor: Offset,
    val placement: CompoundLabelPlacement,
    val align: Offset = IN_CENTER,
    val viewportAligned: Boolean = true,
    /** The size the symbol was rasterized at: `text-size` / `icon-size` at the bucket's zoom + 1. */
    val layoutSize: Float = 1f,
    val featureSizes: FeatureSizes = FeatureSizes(0.0, 0.0),
) {
    /** Assigned by [CrossTileSymbolIndex]; 0 until then. */
    var crossTileID: Long = 0L

    /**
     * An icon.
     *
     * [drawSize] is the size the icon is drawn at -- `icon-size` applied to the sprite's *layout*
     * size, and `icon-text-fit` on top of that. It is deliberately not read back off the placement's
     * bounds, which carry `icon-padding`: padding widens the collision box, it does not stretch the
     * icon.
     */
    class Sprite(
        id: String,
        key: String,
        global: Point,
        tileAnchor: Offset,
        placement: CompoundLabelPlacement,
        val value: ImageBitmap,
        val spriteMeta: SpriteInfo,
        val drawSize: IntSize,
        val opacity: Float = 1f,
        viewportAligned: Boolean = true,
        layoutSize: Float = 1f,
        featureSizes: FeatureSizes = FeatureSizes(0.0, 0.0),
    ) : SymbolInstance(
        id, key, global, tileAnchor, placement,
        viewportAligned = viewportAligned, layoutSize = layoutSize, featureSizes = featureSizes,
    )

    class Text(
        id: String,
        key: String,
        global: Point,
        tileAnchor: Offset,
        placement: CompoundLabelPlacement,
        val value: LabelArt,
        viewportAligned: Boolean = true,
        val spriteAnchorGlobal: Point? = null,
        /** See [SpriteWithText.textOffset]: the label's offset from the icon it was split from. */
        val textOffset: Offset? = null,
        /**
         * The line this label follows, in the bucket's layout space, when `symbol-placement` is
         * `line` or `line-center`. The placement pass projects it to build a collision circle chain.
         */
        val line: List<Pair<Float, Float>>? = null,
        /**
         * **This label's own stretch** of [line], in normalized map coordinates -- the space
         * [global] is in.
         *
         * The draw pass bends the label glyph by glyph *at draw time*, because a placement is held
         * for at least a fade duration and anything baked at commit would step once per cycle
         * through a gesture instead of following the map. It therefore projects this every frame,
         * which is why it is the label's own few vertices and not the whole road: a merged
         * linestring can carry hundreds of vertices and every label on it would re-project all of
         * them, sixty times a second.
         *
         * The stretch is cut generously -- past a label's own drawn length whatever the zoom does
         * within the bucket's level -- and a glyph that still runs off its end falls back to the
         * straight draw.
         */
        val globalLine: List<Point>? = null,
        /**
         * Where the anchor sits in [globalLine]. It is a vertex of it, cut in **before**
         * `text-offset` and `text-translate`: [tileAnchor] has both folded in, which is the point
         * to collide from but not the point to walk the line from -- upstream applies the offset
         * along the path instead (its `lineOffsetX` / `lineOffsetY`).
         */
        val globalAnchorIndex: Int = 0,
        /**
         * `text-offset`'s parts as upstream's `lineOffsetX` / `lineOffsetY`: along the path and
         * along its normal, rather than along the screen's axes as they are for a point label.
         */
        val lineOffsetX: Float = 0f,
        val lineOffsetY: Float = 0f,
        /** `text-keep-upright`, decided in screen space by the draw pass. */
        val keepUpright: Boolean = true,
        layoutSize: Float = 1f,
        featureSizes: FeatureSizes = FeatureSizes(0.0, 0.0),
    ) : SymbolInstance(
        id, key, global, tileAnchor, placement,
        viewportAligned = viewportAligned, layoutSize = layoutSize, featureSizes = featureSizes,
    )

    class SpriteWithText(
        id: String,
        key: String,
        global: Point,
        tileAnchor: Offset,
        placement: CompoundLabelPlacement,
        align: Offset,
        val sprite: ImageBitmap,
        val spriteMeta: SpriteInfo,
        val text: LabelArt,
        val spriteSize: IntSize,
        val textSize: IntSize,
        /**
         * Where the label's centre sits relative to the icon's, in layout pixels.
         *
         * The icon and the label share the feature's anchor, as upstream's `symbol_layout.ts` has
         * them do: this is `text-anchor` plus `text-offset` / `text-radial-offset` /
         * `text-variable-anchor-offset` and `text-translate`, and nothing else -- the icon's own
         * size never enters it.
         */
        val textOffset: Offset,
        val iconOptional: Boolean,
        val textOptional: Boolean,
        val iconOpacity: Float = 1f,
        viewportAligned: Boolean = true,
        val textCandidates: List<TextPlacementCandidate> = emptyList(),
        /** `icon-text-fit`: the icon was stretched around the label, so the label sits inside it. */
        val textInsideIcon: Boolean = false,
        layoutSize: Float = 1f,
        featureSizes: FeatureSizes = FeatureSizes(0.0, 0.0),
        val iconLayoutSize: Float = 1f,
        val iconFeatureSizes: FeatureSizes = FeatureSizes(0.0, 0.0),
    ) : SymbolInstance(
        id, key, global, tileAnchor, placement, align,
        viewportAligned = viewportAligned, layoutSize = layoutSize, featureSizes = featureSizes,
    ) {

        /** This symbol's icon on its own, for when the text could not be placed. */
        fun iconOnly(id: String, placement: CompoundLabelPlacement): Sprite = Sprite(
            id = id,
            key = key,
            global = global,
            tileAnchor = tileAnchor,
            placement = placement,
            value = sprite,
            spriteMeta = spriteMeta,
            drawSize = spriteSize,
            opacity = iconOpacity,
            viewportAligned = viewportAligned,
            layoutSize = iconLayoutSize,
            featureSizes = iconFeatureSizes,
        ).also { it.crossTileID = crossTileID }

        /** This symbol's label on its own, for when the icon could not be placed. */
        fun textOnly(
            id: String,
            global: Point,
            placement: CompoundLabelPlacement,
            spriteAnchorGlobal: Point? = null,
            textOffset: Offset? = null,
        ): Text = Text(
            id = id,
            key = key,
            global = global,
            tileAnchor = tileAnchor,
            placement = placement,
            value = text,
            viewportAligned = viewportAligned,
            spriteAnchorGlobal = spriteAnchorGlobal,
            textOffset = textOffset,
            layoutSize = layoutSize,
            featureSizes = featureSizes,
        ).also { it.crossTileID = crossTileID }

        /** See [spriteWithTextBounds]. */
        val bounds: Rect get() = spriteWithTextBounds(spriteSize, textSize, textOffset)
    }

    companion object {
        val IN_CENTER = Offset(-0.5f, -0.5f)
    }
}

/**
 * The box a [SymbolInstance.SpriteWithText] occupies, in coordinates relative to the **icon's**
 * centre.
 *
 * It is the union of the icon's box, centred on the feature's anchor, and the label's, centred on
 * [textOffset] from it. `SymbolComposer` places a symbol by that box's top-left corner, so the box
 * is what `align` and the draw code are both written against.
 */
internal fun spriteWithTextBounds(spriteSize: IntSize, textSize: IntSize, textOffset: Offset): Rect = Rect(
    left = min(-spriteSize.width / 2f, textOffset.x - textSize.width / 2f),
    top = min(-spriteSize.height / 2f, textOffset.y - textSize.height / 2f),
    right = max(spriteSize.width / 2f, textOffset.x + textSize.width / 2f),
    bottom = max(spriteSize.height / 2f, textOffset.y + textSize.height / 2f),
)
