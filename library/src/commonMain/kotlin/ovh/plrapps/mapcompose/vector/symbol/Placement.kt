package ovh.plrapps.mapcompose.vector.symbol

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import ovh.plrapps.mapcompose.vector.core.ViewportInfo
import ovh.plrapps.mapcompose.vector.renderer.Point
import ovh.plrapps.mapcompose.vector.utils.obb.OBB
import ovh.plrapps.mapcompose.vector.utils.obb.ObbPoint
import ovh.plrapps.mapcompose.vector.utils.obb.Size as ObbSize
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** Upstream's default `fadeDuration` (`src/ui/map.ts`), the length of a symbol's fade in or out. */
internal const val SYMBOL_FADE_DURATION_MS: Long = 300L

/**
 * One symbol that survived placement, together with everything the draw pass needs.
 *
 * Neither the size nor the opacity is baked in here, and both for the same upstream reason: a
 * placement is *held* for a while (see [Placement.stillRecent]), so anything that has to keep
 * following the map between placements has to be a function of the frame rather than of the commit.
 * Upstream's size uniforms are recomputed every frame from `painter.transform.zoom`
 * (`symbol/projection.ts`) while placement collides against its own frozen zoom
 * (`symbol/placement.ts`), and its fade is `fade_opacity[0] + u_fade_change` in
 * `symbol_icon.vertex.glsl`. [textScaleAt] / [iconScaleAt] and [OpacityState.alphaAt] are those two.
 */
internal class PlacedSymbol(
    val instance: SymbolInstance,
    val crossTileID: Long,
    val opacity: JointOpacityState,
    private val textSizeData: SizeData,
    private val iconSizeData: SizeData,
    /** Where this symbol sits in the **draw** order; see [SymbolDrawOrder]. */
    val drawOrder: SymbolDrawOrder = SymbolDrawOrder.NONE,
) {
    /** `text-size` at the zoom being drawn, over the size the label was rasterized at. */
    fun textScaleAt(zoom: Double): Float =
        scaleOf(textSizeData, instance.layoutSize, instance.featureSizes, zoom)

    /** `icon-size` at the zoom being drawn, over the size the icon was laid out at. */
    fun iconScaleAt(zoom: Double): Float =
        scaleOf(iconSizeData, instance.iconLayoutSizeOf(), instance.iconFeatureSizesOf(), zoom)

    /** The same symbol at a later cycle's fade state, for one held over a departing bucket. */
    fun withOpacity(opacity: JointOpacityState): PlacedSymbol =
        PlacedSymbol(instance, crossTileID, opacity, textSizeData, iconSizeData, drawOrder)
}

/**
 * A symbol's place in the **draw** order, which is not the reverse of the placement order.
 *
 * Upstream keeps the two apart and they agree on only one axis. A style layer declared later is
 * placed *first* (it wins the ground) and drawn *last* (it covers), so that axis does invert. A
 * lower `symbol-sort-key` is placed first **and drawn first**, i.e. underneath -- upstream's
 * `tileRenderState.sort((a, b) => a.sortKey - b.sortKey)` in `draw_symbol.ts`, gated on
 * `hasSortKey && bucket.canOverlap`. Drawing the reverse of the placement order therefore stacked
 * ranked icons upside down wherever two of them overlapped.
 *
 * [withinLayer] is ascending in every case: screen y for a [SymbolOrdering.sortFeaturesByY] layer
 * (upstream's `sortFeatures`, which rewrites the index buffers in `getSortedSymbolIndexes` order),
 * the sort key where one is read, and 0 otherwise, leaving [tie] -- the order the source served --
 * to decide. [tie] is *negated* for the y case, upstream's `featureIndexes[b] - featureIndexes[a]`.
 */
internal class SymbolDrawOrder(
    val layerIndex: Int,
    val withinLayer: Double,
    val tie: Int,
) {
    companion object {
        val NONE = SymbolDrawOrder(0, 0.0, 0)

        /** Ascending: earlier is drawn first, and so ends up underneath. */
        val COMPARATOR: Comparator<SymbolDrawOrder> =
            compareBy({ it.layerIndex }, { it.withinLayer }, { it.tie })
    }
}

/** A size property at [zoom], over the size the symbol was laid out at; upstream's `*Scale`. */
internal fun scaleOf(
    sizeData: SizeData,
    layoutSize: Float,
    featureSizes: FeatureSizes,
    zoom: Double,
): Float {
    if (layoutSize <= 0f) return 1f
    val size = evaluateSizeForFeature(sizeData, evaluateSizeForZoom(sizeData, zoom), featureSizes)
    if (size <= 0.0) return 1f
    return (size / layoutSize).toFloat()
}

/**
 * The output of one placement cycle: what to draw, and how long it keeps changing on its own.
 */
internal class PlacementResult(
    val symbols: List<PlacedSymbol>,
    commitTime: Long,
    lastPlacementChangeTime: Long,
    val fadeDurationMs: Long = SYMBOL_FADE_DURATION_MS,
    private val prevZoomAdjustment: Double = 0.0,
) {
    /**
     * Upstream's `u_fade_change` uniform, `Placement.symbolFadeChange(now)`: how far every fade has
     * advanced since the commit, which is what [OpacityState.alphaAt] adds to the committed opacity.
     *
     * [elapsedMs] is measured from the commit rather than given as an absolute time, because the
     * draw pass counts on its own frame clock and that shares no epoch with the clock placement
     * commits on.
     */
    fun fadeChangeAt(elapsedMs: Long): Float =
        if (fadeDurationMs == 0L) 1f
        else (elapsedMs.toDouble() / fadeDurationMs + prevZoomAdjustment).toFloat()

    /**
     * How much longer the fades started by this cycle run, measured from the commit.
     *
     * This is upstream's `hasTransitions` (`now - lastPlacementChangeTime < fadeDuration`) expressed
     * as a duration rather than a predicate on an absolute time. The draw pass counts from its own
     * frame clock, which shares no epoch with the clock placement commits on, so what it can be
     * given is an interval and not an instant.
     */
    val fadeRemainingMs: Long =
        (fadeDurationMs - (commitTime - lastPlacementChangeTime)).coerceIn(0L, fadeDurationMs)

    companion object {
        val Empty = PlacementResult(emptyList(), 0L, -SYMBOL_FADE_DURATION_MS)
    }
}

/**
 * The placement pass, ported from `maplibre-gl-js/src/symbol/placement.ts`.
 *
 * A [SymbolBucket] is laid out once and lives until its tile changes; everything that depends on
 * where the map currently is happens here and only here. For each symbol, in style and
 * `symbol-z-order` order, this projects the bucket's anchor to the screen, scales the symbol to the
 * zoom actually being drawn ([evaluateSizeForZoom]), tests it against [CollisionDetector], and
 * records the decision under its [SymbolInstance.crossTileID]. [commit] then diffs those decisions
 * against the previous cycle's to start the fades.
 *
 * Upstream runs this every frame while anything is fading and uploads the result to a vertex
 * buffer. This port runs it on the viewport's cadence and lets the draw pass interpolate the fade,
 * which is the divergence documented on [OpacityState].
 *
 * Not ported, all for want of a camera or of upstream's defaults: collision *groups*
 * (`crossSourceCollisions` -- upstream defaults to one group for every source, which is what this
 * always does), `perspectiveRatioCutoff`, the globe occlusion tests, and the pitched-label
 * projection in `_projectCollisionBox`.
 */
internal class Placement(
    private val viewportInfo: ViewportInfo,
    /** The **fractional** map zoom, which is what a size expression is evaluated at. */
    private val zoom: Double,
    private val collisionDetectionEnabled: Boolean,
) {
    private val collisionDetector = CollisionDetector(
        viewportWidth = viewportInfo.size.width.toFloat(),
        viewportHeight = viewportInfo.size.height.toFloat(),
    )

    /** The decision per symbol identity, upstream's `placements`. */
    val placements = mutableMapOf<Long, JointPlacement>()

    /** Which `text-variable-anchor` each symbol settled on, upstream's `variableOffsets`. */
    val variableOffsets = mutableMapOf<Long, Int>()

    /** The fade state per symbol identity, filled by [commit]. */
    val opacities = mutableMapOf<Long, JointOpacityState>()

    /** The view this placement was taken against, for "did anything move" -- see [Placement]. */
    val viewport: ViewportInfo = viewportInfo

    private val accepted = mutableListOf<AcceptedSymbol>()
    private val seenCrossTileIDs = mutableSetOf<Long>()
    private var placedOrder: PlacementOrder? = null
    private var commitTime = 0L
    private var lastPlacementChangeTime = 0L
    private var prevZoomAdjustment = 0.0

    /** Upstream's `zoomAtLastRecencyCheck`, read and written by [stillRecent] alone. */
    private var zoomAtLastRecencyCheck = zoom

    /** Upstream's `stale`: inputs changed but a new placement could not start yet. */
    var isStale: Boolean = false
        private set

    private class AcceptedSymbol(
        val instance: SymbolInstance,
        val crossTileID: Long,
        val bucket: SymbolBucket,
        val drawOrder: SymbolDrawOrder,
    )

    /**
     * The draw order of the candidate being placed, which [accept] stamps on what it keeps.
     *
     * A field rather than a parameter because [place] fans out into five branches that all end at
     * [accept], and the order is a property of the candidate, not of the decision.
     */
    private var currentDrawOrder: SymbolDrawOrder = SymbolDrawOrder.NONE

    private val mapRotationDeg: Float = viewportInfo.angleRad * (180f / kotlin.math.PI.toFloat())

    /**
     * Places every symbol of every bucket, in the order [sortForPlacement] gives.
     *
     * [previous] seeds the variable-anchor choice, so a label that had room at its second anchor
     * last cycle tries that one first and does not flick between anchors as the map moves --
     * upstream keeps the same map for the same reason.
     */
    fun placeBuckets(order: PlacementOrder, previous: Placement?) {
        placedOrder = order
        for (candidate in order.candidates) {
            currentDrawOrder = candidate.drawOrder
            place(candidate.bucket, candidate.instance, previous, seenCrossTileIDs)
        }
    }

    private fun place(
        bucket: SymbolBucket,
        instance: SymbolInstance,
        previous: Placement?,
        seenCrossTileIDs: MutableSet<Long>,
    ) {
        val crossTileID = instance.crossTileID

        /* Upstream's `if (seenCrossTileIDs[symbolInstance.crossTileID]) continue`. This is what
         * de-duplicates a label laid out once in every tile it crosses, and it replaced a rule of
         * this port's own that dropped a repeat of the same text within 250 viewport pixels. */
        if (crossTileID != 0L && !seenCrossTileIDs.add(crossTileID)) return

        val iconScale = scaleFor(bucket.iconSizeData, instance.iconLayoutSizeOf(), instance.iconFeatureSizesOf())
        val textScale = scaleFor(bucket.textSizeData, instance.layoutSize, instance.featureSizes)

        val viewportPos = mercatorToViewport(instance.global.x, instance.global.y, viewportInfo)

        if (!collisionDetectionEnabled) {
            accept(instance, crossTileID, bucket, text = true, icon = true, viewportPos)
            return
        }

        when (instance) {
            is SymbolInstance.SpriteWithText -> placeSpriteWithText(
                bucket, instance, crossTileID, iconScale, textScale, viewportPos, previous,
            )

            is SymbolInstance.Sprite -> placeSprite(bucket, instance, crossTileID, iconScale, viewportPos)
            is SymbolInstance.Text -> placeText(bucket, instance, crossTileID, textScale, viewportPos)
        }
    }

    // region per-symbol placement

    private fun placeSpriteWithText(
        bucket: SymbolBucket,
        symbol: SymbolInstance.SpriteWithText,
        crossTileID: Long,
        iconScale: Float,
        textScale: Float,
        viewportPos: Offset,
        previous: Placement?,
    ) {
        val spritePlacement = symbol.placement.spritePlacement
        val spriteViewportPlacement = viewportPlacement(viewportPos, spritePlacement, iconScale)

        if (symbol.textCandidates.isEmpty()) {
            // No candidate list at all: the label keeps its screen-space offset from the icon.
            val textPlacement = symbol.placement.textPlacement
            val textViewportPlacement = textPlacement?.let {
                viewportPlacement(viewportPos + symbol.textOffset * textScale, it, textScale)
            }
            val spriteCanPlace = !collisionDetector.wouldCollide(spriteViewportPlacement)
            val textCanPlace = textViewportPlacement?.let { !collisionDetector.wouldCollide(it) } ?: true

            if (spriteCanPlace && textCanPlace) {
                /* `insert` rather than `tryPlaceLabel` because both halves were already tested:
                 * a padded icon box and its label's always overlap each other, so re-testing after
                 * inserting the icon would reject the label that belongs to it. */
                collisionDetector.insert(spriteViewportPlacement)
                textViewportPlacement?.let { collisionDetector.insert(it) }
                accept(symbol, crossTileID, bucket, text = true, icon = true, viewportPos)
            } else if (spriteCanPlace && symbol.textOptional) {
                collisionDetector.insert(spriteViewportPlacement)
                accept(
                    symbol.iconOnly(symbol.id, CompoundLabelPlacement(spritePlacement, spritePlacement)),
                    crossTileID, bucket, text = false, icon = true, viewportPos,
                )
            } else if (!spriteCanPlace && textCanPlace && symbol.iconOptional &&
                textPlacement != null && textViewportPlacement != null
            ) {
                collisionDetector.insert(textViewportPlacement)
                accept(
                    symbol.textOnly(
                        symbol.id, symbol.global, CompoundLabelPlacement(textPlacement, textPlacement),
                    ),
                    crossTileID, bucket, text = true, icon = false, viewportPos,
                )
            } else {
                reject(crossTileID)
            }
            return
        }

        if (!collisionDetector.wouldCollide(spriteViewportPlacement)) {
            val lastIndex = previous?.variableOffsets?.get(crossTileID)
            val ordered: List<IndexedValue<TextPlacementCandidate>> =
                if (lastIndex != null && lastIndex < symbol.textCandidates.size) {
                    listOf(IndexedValue(lastIndex, symbol.textCandidates[lastIndex])) +
                        symbol.textCandidates.withIndex().filter { it.index != lastIndex }
                } else {
                    symbol.textCandidates.withIndex().toList()
                }

            for ((index, candidate) in ordered) {
                val textVP = mercatorToViewport(candidate.mercatorX, candidate.mercatorY, viewportInfo)
                val textVPPlacement = viewportPlacement(textVP, candidate.labelPlacement, textScale)
                if (collisionDetector.wouldCollide(textVPPlacement)) continue

                collisionDetector.insert(spriteViewportPlacement)
                collisionDetector.insert(textVPPlacement)
                if (crossTileID != 0L) variableOffsets[crossTileID] = index
                accept(
                    symbol.textOnly(
                        id = "${symbol.id}_t",
                        global = Point(candidate.mercatorX, candidate.mercatorY),
                        placement = CompoundLabelPlacement(candidate.labelPlacement, candidate.labelPlacement),
                        spriteAnchorGlobal = symbol.global,
                        /* Unscaled, as everything a held placement hands the draw pass is: the
                         * label's offset from its icon grows and shrinks with `text-size`, which is
                         * a per-frame quantity. */
                        textOffset = Offset(candidate.dx, candidate.dy),
                    ),
                    crossTileID, bucket, text = true, icon = true, viewportPos,
                    extra = symbol.iconOnly("${symbol.id}_s", CompoundLabelPlacement(spritePlacement, null)),
                )
                return
            }

            if (symbol.textOptional) {
                collisionDetector.insert(spriteViewportPlacement)
                accept(
                    symbol.iconOnly(symbol.id, CompoundLabelPlacement(spritePlacement, null)),
                    crossTileID, bucket, text = false, icon = true, viewportPos,
                )
            } else {
                reject(crossTileID)
            }
            return
        }

        if (symbol.iconOptional) {
            for (candidate in symbol.textCandidates) {
                val textVP = mercatorToViewport(candidate.mercatorX, candidate.mercatorY, viewportInfo)
                val textVPPlacement = viewportPlacement(textVP, candidate.labelPlacement, textScale)
                if (collisionDetector.wouldCollide(textVPPlacement)) continue
                collisionDetector.insert(textVPPlacement)
                accept(
                    symbol.textOnly(
                        id = "${symbol.id}_t",
                        global = Point(candidate.mercatorX, candidate.mercatorY),
                        placement = CompoundLabelPlacement(candidate.labelPlacement, candidate.labelPlacement),
                    ),
                    crossTileID, bucket, text = true, icon = false, viewportPos,
                )
                return
            }
        }
        reject(crossTileID)
    }

    private fun placeSprite(
        bucket: SymbolBucket,
        symbol: SymbolInstance.Sprite,
        crossTileID: Long,
        iconScale: Float,
        viewportPos: Offset,
    ) {
        val spritePlacement = symbol.placement.spritePlacement
        val spriteViewportPlacement = viewportPlacement(viewportPos, spritePlacement, iconScale)
        val textViewportPlacement = symbol.placement.textPlacement?.let {
            viewportPlacement(viewportPos, it, iconScale)
        }

        val spriteCanPlace = !collisionDetector.wouldCollide(spriteViewportPlacement)
        val textCanPlace = textViewportPlacement?.let { !collisionDetector.wouldCollide(it) } ?: true

        if (spriteCanPlace && textCanPlace) {
            collisionDetector.insert(spriteViewportPlacement)
            textViewportPlacement?.let { collisionDetector.insert(it) }
            accept(symbol, crossTileID, bucket, text = false, icon = true, viewportPos)
        } else {
            reject(crossTileID)
        }
    }

    private fun placeText(
        bucket: SymbolBucket,
        symbol: SymbolInstance.Text,
        crossTileID: Long,
        textScale: Float,
        viewportPos: Offset,
    ) {
        // textPlacement is always non-null for a Text instance (set in producePointText/produceLineText)
        val resolved = symbol.placement.textPlacement ?: return reject(crossTileID)
        val base = viewportPlacement(viewportPos, resolved, textScale)

        /* A label following a line is a chain of circles, not one rectangle: the straight envelope
         * of a curve claims far more ground than the label covers. */
        val circles = symbol.line?.let { line ->
            circleChain(bucket, symbol, line, viewportPos, textScale)
        }
        val textViewportPlacement = if (circles.isNullOrEmpty()) base else base.copy(circles = circles)

        if (!collisionDetector.wouldCollide(textViewportPlacement)) {
            collisionDetector.insert(textViewportPlacement)
            accept(symbol, crossTileID, bucket, text = true, icon = false, viewportPos)
        } else {
            reject(crossTileID)
        }
    }

    /**
     * The label's collision circles in viewport space, or null when its line does not yield a path.
     *
     * Radius follows upstream's `placeCollisionCircles`: half the label's own height plus its
     * `text-padding`, which the layout pass already folded into the box.
     */
    private fun circleChain(
        bucket: SymbolBucket,
        symbol: SymbolInstance.Text,
        line: List<Pair<Float, Float>>,
        viewportPos: Offset,
        textScale: Float,
    ): List<CollisionCircle>? {
        /* The label is drawn at `textScale` in *screen* pixels, while the walk happens in the
         * bucket's layout space, and the two differ by the bucket's own projection factor -- which
         * is `2^(bucketZoom - displayZoom)`, in (0.5, 1] because `VisibleTilesResolver` rounds the
         * level up. Walking half the layout width, as this used to, made a line label's collision
         * chain up to twice as long as the label it stands for near the bottom of a zoom level. */
        val projectionScale = layoutToViewportScale(bucket)
        if (projectionScale <= 0f) return null
        val halfLength = symbol.value.width * textScale / 2f / projectionScale
        if (halfLength <= 0f) return null
        val path = SymbolProjection.labelPath(line, symbol.tileAnchor, halfLength) ?: return null

        val projected = path.map { p ->
            val n = tileToMercator(bucket, p.x, p.y)
            mercatorToViewport(n.x, n.y, viewportInfo)
        }
        val radius = symbol.placement.textPlacement!!.bounds.height / 2f * textScale
        return SymbolProjection.collisionCircles(
            path = projected,
            radius = radius,
            clipBounds = SymbolProjection.ClipBounds(
                left = -CollisionDetector.VIEWPORT_PADDING,
                top = -CollisionDetector.VIEWPORT_PADDING,
                right = viewportInfo.size.width + CollisionDetector.VIEWPORT_PADDING,
                bottom = viewportInfo.size.height + CollisionDetector.VIEWPORT_PADDING,
            ),
        ).takeIf { it.isNotEmpty() }
    }

    // endregion

    private fun accept(
        instance: SymbolInstance,
        crossTileID: Long,
        bucket: SymbolBucket,
        text: Boolean,
        icon: Boolean,
        viewportPos: Offset,
        extra: SymbolInstance? = null,
    ) {
        /* [extra] first: it is the icon of a label placed at a `text-variable-anchor`, the two share
         * one [SymbolDrawOrder], and the draw pass sorts stably -- so whatever goes in first is
         * drawn first, and a label belongs above the icon it names. */
        if (extra != null) accepted += AcceptedSymbol(extra, crossTileID, bucket, currentDrawOrder)
        accepted += AcceptedSymbol(instance, crossTileID, bucket, currentDrawOrder)
        if (crossTileID != 0L) {
            placements[crossTileID] = JointPlacement(
                text = text,
                icon = icon,
                skipFade = isOffViewport(viewportPos),
            )
        }
    }

    private fun reject(crossTileID: Long) {
        if (crossTileID != 0L) {
            placements[crossTileID] = JointPlacement(text = false, icon = false, skipFade = false)
        }
    }

    /**
     * Upstream's `skipFade`: outside the viewport but inside the collision index's padding, so the
     * symbol is not on screen yet and can appear at full opacity when a pan brings it in.
     */
    private fun isOffViewport(viewportPos: Offset): Boolean =
        viewportPos.x < 0f || viewportPos.y < 0f ||
            viewportPos.x > viewportInfo.size.width || viewportPos.y > viewportInfo.size.height

    // region commit

    /**
     * Diffs this cycle's decisions against [previous] and starts the fades, ported from `commit`.
     *
     * A symbol that has gone away is carried over at falling opacity until it is hidden, which is
     * what makes a label fade *out* rather than vanish when it loses its ground.
     */
    fun commit(previous: Placement?, now: Long) {
        commitTime = now
        var placementChanged = false

        prevZoomAdjustment = previous?.zoomAdjustment(zoom) ?: 0.0
        val increment = previous?.symbolFadeChange(now)?.toFloat() ?: 1f

        val prevOpacities = previous?.opacities.orEmpty()
        val prevOffsets = previous?.variableOffsets.orEmpty()

        for ((crossTileID, jointPlacement) in placements) {
            val prevOpacity = prevOpacities[crossTileID]
            if (prevOpacity != null) {
                opacities[crossTileID] = JointOpacityState.create(
                    prevOpacity, increment, jointPlacement.text, jointPlacement.icon,
                )
                placementChanged = placementChanged ||
                    jointPlacement.text != prevOpacity.text.placed ||
                    jointPlacement.icon != prevOpacity.icon.placed
            } else {
                opacities[crossTileID] = JointOpacityState.create(
                    null, increment, jointPlacement.text, jointPlacement.icon, jointPlacement.skipFade,
                )
                placementChanged = placementChanged || jointPlacement.text || jointPlacement.icon
            }
        }

        for ((crossTileID, prevOpacity) in prevOpacities) {
            if (crossTileID in opacities) continue
            val jointOpacity = JointOpacityState.create(prevOpacity, increment, placedText = false, placedIcon = false)
            if (!jointOpacity.isHidden()) {
                opacities[crossTileID] = jointOpacity
                placementChanged = placementChanged || prevOpacity.text.placed || prevOpacity.icon.placed
            }
        }

        for ((crossTileID, offset) in prevOffsets) {
            if (crossTileID !in variableOffsets && opacities[crossTileID]?.isHidden() == false) {
                variableOffsets[crossTileID] = offset
            }
        }

        /* The start time of the last fade, which is what `hasTransitions` measures against. */
        lastPlacementChangeTime = when {
            placementChanged -> now
            previous != null -> previous.lastPlacementChangeTime
            else -> now
        }
    }

    /** Upstream's `symbolFadeChange`: the fraction of a fade that elapsed since this commit. */
    fun symbolFadeChange(now: Long): Double =
        if (SYMBOL_FADE_DURATION_MS == 0L) 1.0
        else (now - commitTime).toDouble() / SYMBOL_FADE_DURATION_MS + prevZoomAdjustment

    /**
     * Upstream's `zoomAdjustment`, with its reason:
     *
     * > When zooming out quickly, labels can overlap each other. This adjustment is used to reduce
     * > the interval between placement calculations and to reduce the fade duration when zooming out
     * > quickly. Discovering the collisions more quickly and fading them more quickly reduces the
     * > unwanted effect.
     */
    private fun zoomAdjustment(newZoom: Double): Double = max(0.0, (zoom - newZoom) / 1.5)

    /**
     * Whether this placement is recent enough that a new one must not start yet, ported from
     * `stillRecent` (`symbol/placement.ts`) with upstream's comment:
     *
     * > The adjustment makes placement more frequent when zooming. This condition applies the
     * > adjustment only after the map has stopped zooming. This avoids adding extra jank while
     * > zooming.
     *
     * This is what keeps a placement *held* rather than recomputed on every viewport update, and so
     * what stops near-threshold labels re-deciding -- shimmering -- sixty times a second through a
     * pinch. It mutates [zoomAtLastRecencyCheck], so it has to be called exactly once per cycle, as
     * upstream calls it once inside `placementSettled`.
     */
    fun stillRecent(now: Long, newZoom: Double): Boolean {
        val durationAdjustment =
            if (zoomAtLastRecencyCheck == newZoom) 1.0 - zoomAdjustment(newZoom) else 1.0
        zoomAtLastRecencyCheck = newZoom
        return commitTime + SYMBOL_FADE_DURATION_MS * durationAdjustment > now
    }

    /**
     * Upstream's `setStale`, with its reason: "the last placement finished running, but the next one
     * hasn't started yet because of the `stillRecent` check, so mark it stale to ensure that we
     * request another render frame".
     */
    fun setStale() {
        isStale = true
    }

    /** How long until [stillRecent] lapses, so a deferred placement can be retried then. */
    fun recencyRemainingMs(now: Long): Long =
        (commitTime + SYMBOL_FADE_DURATION_MS - now).coerceAtLeast(0L)

    /**
     * What the draw pass consumes: everything placed this cycle, plus everything still fading out.
     *
     * The second half matters, and has two sources.
     *
     * A symbol that lost its ground but is still in the current buckets is found among
     * [placedOrder]'s candidates -- upstream's `updateBucketOpacities`, which walks a bucket's whole
     * instance list rather than only what was placed.
     *
     * A symbol whose **bucket has left the visible set** is not among those candidates at all, and
     * dropping it is what made labels blink out in one frame instead of fading: a pan or a zoom
     * swaps the bucket set wholesale, and every symbol on a departing tile disappeared instantly
     * however opaque it still was. Upstream never sees this because a departing tile is *held*:
     * `Tile.holdingForSymbolFade` keeps it in the render set, so its bucket is still walked while
     * its symbols fade. There is no tile lifecycle to hook here -- buckets are cached by the
     * rasterizer -- so the symbols are held instead, carried over from [previous]'s result for as
     * long as [commit] keeps their opacity alive. That is bounded: each commit advances the fade, so
     * a held symbol is hidden and dropped within one fade duration.
     *
     * [previous]'s own symbols are preferred over the candidates for both of those, and for two
     * reasons that are the same reason: what it holds is *what was actually drawn last cycle*.
     *
     * - A [SymbolInstance.SpriteWithText] placed at a `text-variable-anchor` is accepted as **two**
     *   entries sharing one [SymbolInstance.crossTileID] -- the label and the icon it names -- and a
     *   carry-over that de-duplicates by id drops one of them, so the icon vanished in a single frame
     *   while its label faded out over 300 ms.
     * - The candidate for such a symbol is the bucket's original, *un-split* instance, whose label
     *   sits at the plain `text-offset` rather than at the anchor the symbol settled on. Redrawing it
     *   from there makes the label jump for the length of its fade. Upstream keeps the position it
     *   drew, which is why [commit] keeps [variableOffsets] for every symbol that is not yet hidden.
     *
     * The one thing a carried symbol does not do is compete: it is drawn, not re-collided. That is
     * upstream's `placeLayerBucketPart`, which marks a held tile's symbols
     * `new JointPlacement(false, false, false)` and deliberately leaves them out of
     * `seenCrossTileIDs`, so a live copy in another tile still wins the ground.
     */
    fun result(previous: Placement? = null): PlacementResult {
        val placedIDs = accepted.mapTo(mutableSetOf()) { it.crossTileID }
        val symbols = accepted.mapTo(mutableListOf()) { a ->
            PlacedSymbol(
                instance = a.instance,
                crossTileID = a.crossTileID,
                opacity = opacities[a.crossTileID] ?: fullyVisible,
                textSizeData = a.bucket.textSizeData,
                iconSizeData = a.bucket.iconSizeData,
                drawOrder = a.drawOrder,
            )
        }

        /* Not de-duplicated within this loop: a symbol drawn as an icon *and* a label is two entries
         * under one id, and both halves have to fade together. */
        val carried = mutableSetOf<Long>()
        for (held in previous?.lastResult?.symbols.orEmpty()) {
            val crossTileID = held.crossTileID
            if (crossTileID == 0L || crossTileID in placedIDs) continue
            val opacity = opacities[crossTileID] ?: continue
            if (opacity.isHidden()) continue
            carried += crossTileID
            symbols += held.withOpacity(opacity)
        }

        for (candidate in placedOrder?.candidates.orEmpty()) {
            val crossTileID = candidate.instance.crossTileID
            if (crossTileID == 0L || crossTileID in placedIDs || crossTileID in carried) continue
            val opacity = opacities[crossTileID] ?: continue
            if (opacity.isHidden()) continue
            carried += crossTileID
            symbols += PlacedSymbol(
                instance = candidate.instance,
                crossTileID = crossTileID,
                opacity = opacity,
                textSizeData = candidate.bucket.textSizeData,
                iconSizeData = candidate.bucket.iconSizeData,
                drawOrder = candidate.drawOrder,
            )
        }

        /* Stable, and that is load-bearing: an icon and the label it names share one draw order, and
         * the icon was accepted first precisely so it stays underneath. A symbol carried over from
         * [previous] keeps the order it was drawn with. */
        return PlacementResult(
            symbols = symbols.sortedWith(compareBy(SymbolDrawOrder.COMPARATOR) { it.drawOrder }),
            commitTime = commitTime,
            lastPlacementChangeTime = lastPlacementChangeTime,
            prevZoomAdjustment = prevZoomAdjustment,
        ).also { lastResult = it }
    }

    /** The result this placement produced, which the next one carries departing symbols over from. */
    private var lastResult: PlacementResult? = null

    /** The state a symbol with no identity is drawn at: visible, and never fading. */
    private val fullyVisible: JointOpacityState
        get() = JointOpacityState.create(null, 1f, placedText = true, placedIcon = true, skipFade = true)

    // endregion

    // region geometry

    /**
     * A collision box in viewport coordinates, at the size the zoom being drawn asks for.
     *
     * [scale] is upstream's `textPixelRatio`/`iconScale` in `placeCollisionBox`: the box was built
     * at the bucket's layout size and has to be scaled to what is actually drawn.
     *
     * The map's bearing is added only to a symbol whose own angle is non-zero -- a line label, which
     * rotates with the map. A point symbol's rotation alignment defaults to `viewport`, so it keeps
     * angle 0 however the map is turned.
     */
    private fun viewportPlacement(
        center: Offset,
        original: LabelPlacement,
        scale: Float,
    ): LabelPlacement {
        val rectWidth = original.bounds.width * scale
        val rectHeight = original.bounds.height * scale
        val ownAngle = original.angle
        val effectiveAngle = if (ownAngle != 0f) ownAngle + mapRotationDeg else ownAngle

        return original.copy(
            position = ObbPoint(center.x, center.y),
            bounds = Rect(
                left = center.x - rectWidth / 2f,
                top = center.y - rectHeight / 2f,
                right = center.x + rectWidth / 2f,
                bottom = center.y + rectHeight / 2f,
            ),
            obb = OBB(
                center = ObbPoint(center.x, center.y),
                size = ObbSize(rectWidth, rectHeight),
                rotation = effectiveAngle,
            ),
            circles = null,
        )
    }

    /**
     * A size property at *this placement's* zoom -- the snapshot the collision boxes are built
     * against, which upstream also freezes (`placement.ts` evaluates the size at
     * `this.transform.zoom`, not at the frame's). The draw pass uses [PlacedSymbol.textScaleAt]
     * instead.
     */
    private fun scaleFor(sizeData: SizeData, layoutSize: Float, featureSizes: FeatureSizes): Float =
        scaleOf(sizeData, layoutSize, featureSizes, zoom)

    /** A point in a bucket's layout space, as a normalized Mercator coordinate. */
    /**
     * Screen pixels per layout pixel for [bucket]: the factor a length measured in its layout space
     * grows by on the way to the viewport, which is uniform because both steps are pure scales.
     */
    private fun layoutToViewportScale(bucket: SymbolBucket): Float {
        val n = 2.0.pow(bucket.ref.z.toDouble())
        val mercatorPerLayoutPx = 1.0 / (bucket.canvasSize.toDouble() * n)
        return (mercatorPerLayoutPx * viewportInfo.fullWidth.toDouble() * viewportInfo.scale).toFloat()
    }

    private fun tileToMercator(bucket: SymbolBucket, x: Float, y: Float): Point {
        val n = 2.0.pow(bucket.ref.z.toDouble())
        val size = bucket.canvasSize.toDouble()
        return Point(
            (bucket.ref.x * size + x) / (size * n),
            (bucket.ref.y * size + y) / (size * n),
        )
    }

    // endregion

}

/** One symbol queued for placement, together with the bucket that laid it out. */
internal class PlacementCandidate(
    val bucket: SymbolBucket,
    val instance: SymbolInstance,
    /** Where this symbol goes in the draw order, which is *not* this list reversed. */
    val drawOrder: SymbolDrawOrder,
)

/**
 * The order symbols are **placed** in: whichever comes first gets the ground it asks for.
 *
 * Style order dominates -- a layer declared later in the style is placed first, upstream's
 * `PauseablePlacement` walking `style._order` backwards -- and `symbol-z-order` decides the order
 * *within* a layer, as upstream's `SymbolBucket` flags do ([SymbolOrdering]):
 *
 * - `symbol-sort-key` ascending whenever the layer has a per-feature one and `symbol-z-order` is not
 *   `viewport-y` -- **`source` included**, which is upstream's `zOrder !== 'viewport-y' &&
 *   !sortKey.isConstant()` and not the "source means no ordering" this port used to have;
 * - screen y descending for `symbol-z-order: viewport-y`, so a symbol nearer the bottom of the map
 *   is placed first. Upstream's `placeLayerBucketPart` walks `getSortedSymbolIndexes`' ascending
 *   list backwards, and that list is keyed on the anchor rotated **into viewport space**
 *   (`sin * anchorX + cos * anchorY`), not on the geographic y this port used to sort by -- which is
 *   why nothing re-stacked when the map was rotated. Only a literal `viewport-y` reorders the
 *   placement: `auto` does not, however the draw order reads it;
 * - the order the source served, otherwise.
 *
 * The final tie-break is production order, which makes a run reproducible.
 *
 * The **draw** order is computed here too, once, because it reads the same flags off the same
 * buckets -- see [SymbolDrawOrder] for why it is not this order reversed.
 *
 * Only a `viewport-y` or [SymbolOrdering.sortFeaturesByY] layer makes any of this depend on the
 * view; [matches] therefore keeps the cached order across viewport updates unless one of those is
 * present, which is upstream's `sortedAngle` cache with a wider key.
 */
internal class PlacementOrder(
    private val buckets: List<SymbolBucket>,
    private val viewportInfo: ViewportInfo,
) {

    private val isViewDependent: Boolean = buckets.any { it.ordering.isViewDependent }

    val candidates: List<PlacementCandidate> = buckets
        .flatMap { bucket -> bucket.instances.map { bucket to it } }
        .withIndex()
        .map { (index, pair) ->
            val (bucket, instance) = pair
            IndexedValue(index, PlacementCandidate(bucket, instance, drawOrderOf(bucket, instance, index)))
        }
        .sortedWith(
            compareByDescending<IndexedValue<PlacementCandidate>> {
                it.value.bucket.layerIndex
            }.thenBy {
                placementOrderOf(it.value.bucket, it.value.instance)
            }.thenBy {
                it.index
            }
        )
        .map { it.value }

    /**
     * Whether this order still describes [other] at [viewport].
     *
     * The buckets are compared by identity, as the cache hands them back; the viewport only matters
     * to a layer that orders by screen y.
     */
    fun matches(other: List<SymbolBucket>, viewport: ViewportInfo): Boolean =
        buckets.size == other.size &&
            buckets.indices.all { buckets[it] === other[it] } &&
            (!isViewDependent || viewportInfo == viewport)

    /** The placement comparator's key for one symbol; lower is placed first. */
    private fun placementOrderOf(bucket: SymbolBucket, instance: SymbolInstance): Double {
        val ordering = bucket.ordering
        return when {
            ordering.sortFeaturesByKey -> instance.placement.spritePlacement.inLayerPriority
            ordering.placeByViewportY -> -screenY(instance)
            else -> 0.0
        }
    }

    /** See [SymbolDrawOrder]; ascending, so the first entry is drawn underneath. */
    private fun drawOrderOf(bucket: SymbolBucket, instance: SymbolInstance, index: Int): SymbolDrawOrder {
        val ordering = bucket.ordering
        return when {
            ordering.sortFeaturesByY ->
                SymbolDrawOrder(bucket.layerIndex, screenY(instance), -index)

            ordering.hasSortKey && ordering.canOverlap ->
                SymbolDrawOrder(bucket.layerIndex, instance.placement.spritePlacement.inLayerPriority, index)

            else -> SymbolDrawOrder(bucket.layerIndex, 0.0, index)
        }
    }

    /**
     * The symbol's anchor in viewport pixels, upstream's rotated `sin * anchorX + cos * anchorY`.
     *
     * Projecting rather than rotating tile units costs one transform per symbol per rebuild -- paid
     * only by a style that actually asks for y ordering -- and in exchange the world wrap of an
     * infinite-scroll map is handled by the same arithmetic everything else here uses.
     */
    private fun screenY(instance: SymbolInstance): Double =
        mercatorToViewport(instance.global.x, instance.global.y, viewportInfo).y.toDouble()
}

/** A [SymbolInstance]'s icon layout size, which only a combined symbol carries separately. */
internal fun SymbolInstance.iconLayoutSizeOf(): Float = when (this) {
    is SymbolInstance.SpriteWithText -> iconLayoutSize
    else -> layoutSize
}

/** See [iconLayoutSizeOf]. */
internal fun SymbolInstance.iconFeatureSizesOf(): FeatureSizes = when (this) {
    is SymbolInstance.SpriteWithText -> iconFeatureSizes
    else -> featureSizes
}

/**
 * Normalized Mercator to viewport (screen) pixels, at the current scale and rotation.
 */
internal fun mercatorToViewport(
    mercatorX: Double,
    mercatorY: Double,
    viewportInfo: ViewportInfo,
): Offset {
    var deltaX = mercatorX - viewportInfo.centroidX
    if (viewportInfo.infiniteScrollX) {
        if (deltaX > 0.5) deltaX -= 1.0
        else if (deltaX < -0.5) deltaX += 1.0
    }
    val deltaY = mercatorY - viewportInfo.centroidY

    val viewportCenterX = viewportInfo.size.width.toDouble() / 2.0
    val viewportCenterY = viewportInfo.size.height.toDouble() / 2.0

    // Scale the map-space delta to screen pixels
    val scaledDX = deltaX * viewportInfo.fullWidth.toDouble() * viewportInfo.scale
    val scaledDY = deltaY * viewportInfo.fullHeight.toDouble() * viewportInfo.scale

    // Compose rotate(θ) is clockwise, matching x'=dx*cos(θ)-dy*sin(θ); use +angleRad.
    val angle = viewportInfo.angleRad.toDouble()
    return Offset(
        (viewportCenterX + scaledDX * cos(angle) - scaledDY * sin(angle)).toFloat(),
        (viewportCenterY + scaledDX * sin(angle) + scaledDY * cos(angle)).toFloat(),
    )
}
