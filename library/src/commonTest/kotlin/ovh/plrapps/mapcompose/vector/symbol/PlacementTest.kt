package ovh.plrapps.mapcompose.vector.symbol

import androidx.compose.ui.geometry.Offset
import ovh.plrapps.mapcompose.vector.renderer.Point
import kotlin.math.PI
import kotlin.math.pow
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import ovh.plrapps.mapcompose.vector.spec.style.SYMBOL_Z_ORDER_SOURCE
import ovh.plrapps.mapcompose.vector.spec.style.SYMBOL_Z_ORDER_VIEWPORT_Y
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The placement pass: what survives collision, in what order, at what size, and how it fades.
 *
 * Upstream's `placement.test.ts` is not transcribed, for the same reason its `*_bucket` tests are
 * not: it drives `Placement` through a `Style`, a `Transform` and a GL `SymbolBucket`, none of which
 * has an analogue here. The behaviour it covers is asserted directly instead.
 */
class PlacementTest {

    private val index = CrossTileSymbolIndex()

    /** Assigns cross-tile ids, places, and commits -- one cycle, as `VectorRasterizer.place` runs it. */
    private fun place(
        buckets: List<SymbolBucket>,
        viewportInfo: ovh.plrapps.mapcompose.vector.core.ViewportInfo = SymbolFixtures.viewport(),
        zoom: Double = 6.0,
        previous: Placement? = null,
        now: Long = 0L,
        collisionDetectionEnabled: Boolean = true,
    ): Placement {
        for ((layerId, layerBuckets) in buckets.groupBy { it.layerId }) {
            index.addLayer(layerId, layerBuckets, density = 1f)
        }
        val placement = Placement(viewportInfo, zoom, collisionDetectionEnabled)
        placement.placeBuckets(PlacementOrder(buckets, viewportInfo), previous)
        placement.commit(previous, now)
        return placement
    }

    private fun placedTexts(placement: Placement): List<String> =
        placement.result().symbols.map { it.instance.key }

    /** A symbol anchored inside the tile at [z]/[tx]/[ty] that covers [mx]/[my] in Mercator. */
    private fun instanceIn(text: String, mx: Double, my: Double, z: Int, tx: Int, ty: Int) =
        SymbolFixtures.textInstance(
            key = text,
            global = Point(mx, my),
            tileAnchor = androidx.compose.ui.geometry.Offset(
                (((mx * 2.0.pow(z)) - tx) * LAYOUT_TILE_SIZE).toFloat(),
                (((my * 2.0.pow(z)) - ty) * LAYOUT_TILE_SIZE).toFloat(),
            ),
            width = 40f, height = 12f,
        )

    @Test
    fun `a line label claims the ground it is drawn on and not the ground it was laid out on`() {
        /* The collision chain is walked in the bucket's layout space and then projected, and the
         * two differ by `2^(bucketZoom - displayZoom)`, in (0.5, 1] because `VisibleTilesResolver`
         * rounds the level up. Walking half the *layout* width, as this used to, made a line
         * label's chain that factor too short -- here half as long as the label it stands for, so
         * two labels the eye sees overlapping were both placed. */
        val line = listOf(0f to 256f, 512f to 256f)
        val tileOrigin = 8.0 * LAYOUT_TILE_SIZE
        val world = LAYOUT_TILE_SIZE * 64.0
        fun lineLabel(key: String, atX: Float) = SymbolFixtures.textInstance(
            key = key,
            global = Point((tileOrigin + atX) / world, (tileOrigin + 256f) / world),
            tileAnchor = Offset(atX, 256f),
            width = 40f,
            height = 12f,
            line = line,
        )

        // Half the layout-to-viewport factor, so the two spaces disagree by a factor of two.
        val viewport = SymbolFixtures.viewport(
            centroid = Point((tileOrigin + 140f) / world, (tileOrigin + 256f) / world),
            worldPx = (world / 2).toInt(),
        )
        val placement = place(
            listOf(SymbolFixtures.bucket(listOf(lineLabel("a", 100f), lineLabel("b", 180f)))),
            viewportInfo = viewport,
        )

        // 40 screen pixels apart, each label 40 screen pixels wide: only one of them fits.
        assertEquals(1, placedTexts(placement).size, "the two chains overlap on screen")
    }

    @Test
    fun `a symbol blocks a neighbour whose box overlaps its own`() {
        val bucket = SymbolFixtures.bucket(
            listOf(
                SymbolFixtures.textInstance("first", global = Point(0.5, 0.5)),
                SymbolFixtures.textInstance("second", global = Point(0.505, 0.5)),
            )
        )

        val placement = place(listOf(bucket))

        assertContentEquals(listOf("first"), placedTexts(placement))
    }

    @Test
    fun `a symbol far enough away is placed too`() {
        val bucket = SymbolFixtures.bucket(
            listOf(
                SymbolFixtures.textInstance("first", global = Point(0.5, 0.5)),
                SymbolFixtures.textInstance("second", global = Point(0.55, 0.5)),
            )
        )

        val placement = place(listOf(bucket))

        assertContentEquals(listOf("first", "second"), placedTexts(placement))
    }

    @Test
    fun `a later style layer is placed before an earlier one`() {
        /* Style order dominates every within-layer rule: a layer declared later wins the ground. */
        val early = SymbolFixtures.bucket(
            listOf(SymbolFixtures.textInstance("early", global = Point(0.5, 0.5), layerIndex = 0)),
            layerIndex = 0, layerId = "early",
        )
        val late = SymbolFixtures.bucket(
            listOf(SymbolFixtures.textInstance("late", global = Point(0.505, 0.5), layerIndex = 1)),
            layerIndex = 1, layerId = "late",
        )

        val placement = place(listOf(early, late))

        assertContentEquals(listOf("late"), placedTexts(placement))
    }

    @Test
    fun `symbol-sort-key orders within a layer`() {
        val bucket = SymbolFixtures.bucket(
            listOf(
                SymbolFixtures.textInstance("low priority", global = Point(0.5, 0.5), sortKey = 5.0),
                SymbolFixtures.textInstance("high priority", global = Point(0.505, 0.5), sortKey = 1.0),
            ),
            ordering = SymbolFixtures.ordering(hasSortKey = true),
        )

        val placement = place(listOf(bucket))

        assertContentEquals(listOf("high priority"), placedTexts(placement), "the lower key is placed first")
    }

    @Test
    fun `symbol-z-order source still orders by the sort key`() {
        /* Upstream's flag is `zOrder !== 'viewport-y' && !sortKey.isConstant()`, so only `viewport-y`
         * suppresses the key -- `source` decides the *y* question, not the key one. */
        val bucket = SymbolFixtures.bucket(
            listOf(
                SymbolFixtures.textInstance("first in tile", global = Point(0.5, 0.5), sortKey = 5.0),
                SymbolFixtures.textInstance("second in tile", global = Point(0.505, 0.5), sortKey = 1.0),
            ),
            ordering = SymbolFixtures.ordering(zOrder = SYMBOL_Z_ORDER_SOURCE, hasSortKey = true),
        )

        val placement = place(listOf(bucket))

        assertContentEquals(listOf("second in tile"), placedTexts(placement))
    }

    @Test
    fun `symbol-z-order source keeps the tile's order when no sort key varies`() {
        val bucket = SymbolFixtures.bucket(
            listOf(
                SymbolFixtures.textInstance("first in tile", global = Point(0.5, 0.5)),
                SymbolFixtures.textInstance("second in tile", global = Point(0.5, 0.495)),
            ),
            ordering = SymbolFixtures.ordering(zOrder = SYMBOL_Z_ORDER_SOURCE),
        )

        val placement = place(listOf(bucket))

        assertContentEquals(listOf("first in tile"), placedTexts(placement), "y does not enter it")
    }

    @Test
    fun `symbol-z-order viewport-y places the lower symbol first`() {
        val bucket = SymbolFixtures.bucket(
            listOf(
                SymbolFixtures.textInstance("higher up", global = Point(0.5, 0.5)),
                SymbolFixtures.textInstance("further down", global = Point(0.5, 0.505)),
            ),
            ordering = SymbolFixtures.ordering(zOrder = SYMBOL_Z_ORDER_VIEWPORT_Y),
        )

        val placement = place(listOf(bucket))

        assertContentEquals(listOf("further down"), placedTexts(placement))
    }

    @Test
    fun `symbol-z-order viewport-y follows the rotated viewport and not the geographic y`() {
        /* Upstream keys on `sin * anchorX + cos * anchorY` at the map's bearing. Turned a quarter
         * turn, the symbol further *east* is the one lower on screen and takes the ground. */
        val bucket = SymbolFixtures.bucket(
            listOf(
                SymbolFixtures.textInstance("west", global = Point(0.495, 0.5)),
                SymbolFixtures.textInstance("east", global = Point(0.505, 0.5)),
            ),
            ordering = SymbolFixtures.ordering(zOrder = SYMBOL_Z_ORDER_VIEWPORT_Y),
        )

        val turned = SymbolFixtures.viewport(angleRad = (PI / 2).toFloat())

        assertContentEquals(listOf("east"), placedTexts(place(listOf(bucket), viewportInfo = turned)))
    }

    @Test
    fun `symbol-z-order auto does not order by y when the layer forbids overlap`() {
        /* `sortFeaturesByY = zOrderByViewportY && canOverlap`, and the placement pass reorders for a
         * literal `viewport-y` alone -- so a default layer is placed in the order the source served,
         * however its symbols sit on screen. */
        val bucket = SymbolFixtures.bucket(
            listOf(
                SymbolFixtures.textInstance("first in tile", global = Point(0.5, 0.5)),
                SymbolFixtures.textInstance("further down", global = Point(0.5, 0.505)),
            )
        )

        val placement = place(listOf(bucket))

        assertContentEquals(listOf("first in tile"), placedTexts(placement))
    }

    @Test
    fun `the lower sort key is drawn underneath`() {
        /* The draw order is not the placement order reversed: upstream places the lower key first
         * *and* draws it first, `tileRenderState.sort((a, b) => a.sortKey - b.sortKey)`. */
        val bucket = SymbolFixtures.bucket(
            listOf(
                SymbolFixtures.textInstance(
                    "low priority", global = Point(0.5, 0.5), sortKey = 5.0,
                    overlapMode = OverlapMode.Always,
                ),
                SymbolFixtures.textInstance(
                    "high priority", global = Point(0.505, 0.5), sortKey = 1.0,
                    overlapMode = OverlapMode.Always,
                ),
            ),
            ordering = SymbolFixtures.ordering(hasSortKey = true, canOverlap = true),
        )

        val placement = place(listOf(bucket))

        assertContentEquals(listOf("high priority", "low priority"), placedTexts(placement))
    }

    @Test
    fun `a later style layer is placed first and drawn last`() {
        val early = SymbolFixtures.bucket(
            listOf(SymbolFixtures.textInstance("early", overlapMode = OverlapMode.Always)),
            layerIndex = 0, layerId = "early",
        )
        val late = SymbolFixtures.bucket(
            listOf(
                SymbolFixtures.textInstance(
                    "late", global = Point(0.505, 0.5), layerIndex = 1,
                    overlapMode = OverlapMode.Always,
                )
            ),
            layerIndex = 1, layerId = "late",
        )

        val placement = place(listOf(early, late))

        assertContentEquals(listOf("early", "late"), placedTexts(placement), "style order, bottom up")
    }

    @Test
    fun `the same label in two tiles is placed once`() {
        /* This is what retired `MIN_LINE_LABEL_REPEAT_DIST`: the cross-tile index gives both copies
         * one identity, and the placement pass skips a `crossTileID` it has already seen. */
        val parent = SymbolFixtures.bucket(
            listOf(SymbolFixtures.textInstance("Main Street", global = Point(0.5, 0.5))),
            z = 6, x = 8, y = 8,
        )
        val child = SymbolFixtures.bucket(
            listOf(
                SymbolFixtures.textInstance(
                    "Main Street",
                    global = Point(0.5, 0.5),
                    tileAnchor = Offset(LAYOUT_TILE_SIZE.toFloat(), LAYOUT_TILE_SIZE.toFloat()),
                )
            ),
            z = 7, x = 16, y = 16,
        )

        val placement = place(listOf(parent, child))

        assertEquals(1, placement.result().symbols.size)
    }

    @Test
    fun `a symbol outside the padded viewport is refused`() {
        val bucket = SymbolFixtures.bucket(
            listOf(SymbolFixtures.textInstance("offscreen", global = Point(2.0, 0.5)))
        )

        val placement = place(listOf(bucket))

        assertTrue(placement.result().symbols.isEmpty())
    }

    @Test
    fun `collision detection off places everything`() {
        val bucket = SymbolFixtures.bucket(
            listOf(
                SymbolFixtures.textInstance("first", global = Point(0.5, 0.5)),
                SymbolFixtures.textInstance("second", global = Point(0.5, 0.5)),
            )
        )

        val placement = place(listOf(bucket), collisionDetectionEnabled = false)

        assertEquals(2, placement.result().symbols.size)
    }

    @Test
    fun `a symbol is drawn at the size the frame's zoom asks for and not the placement's`() {
        /* The label was rasterized at the bucket's own size; the scale is what the zoom being drawn
         * wants over that, and is upstream's `textScale`. It is a property of the *frame*, not of
         * the placement: a placement is held for up to a fade duration, and the labels have to keep
         * growing and shrinking with the map while it is. Upstream recomputes its `u_size` uniforms
         * every frame for the same reason. */
        val bucket = SymbolFixtures.bucket(
            listOf(SymbolFixtures.textInstance("label", layoutSize = 16f)),
            textSizeData = SizeData.Camera(
                zoomStops = listOf(5.0, 7.0),
                sizes = listOf(8.0, 16.0),
                layoutSize = 16.0,
                interpolationType = ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.InterpolationType.Linear,
            ),
        )

        val placed = place(listOf(bucket), zoom = 6.0).result().symbols.single()

        assertEquals(12f / 16f, placed.textScaleAt(6.0), 1e-4f)
        assertEquals(1f, placed.textScaleAt(7.0), 1e-4f, "the same placement, a later frame")
        assertEquals(8f / 16f, placed.textScaleAt(5.0), 1e-4f)
    }

    @Test
    fun `a newly placed symbol fades in and one that loses its ground fades out`() {
        val visible = SymbolFixtures.bucket(
            listOf(SymbolFixtures.textInstance("label", global = Point(0.5, 0.5)))
        )

        val first = place(listOf(visible), now = 0L)
        assertEquals(0f, first.result().symbols.single().opacity.text.opacity, "fades in from nothing")
        assertTrue(first.result().fadeRemainingMs > 0L)

        // A second cycle, a full fade later, with the label still placed.
        val second = place(listOf(visible), previous = first, now = SYMBOL_FADE_DURATION_MS)
        assertEquals(1f, second.result().symbols.single().opacity.text.opacity, 1e-4f)
        assertEquals(0L, second.result().fadeRemainingMs, "nothing is moving any more")

        // Now a rival in the layer above takes the ground.
        val rival = SymbolFixtures.bucket(
            listOf(SymbolFixtures.textInstance("rival", global = Point(0.5, 0.5), layerIndex = 1)),
            layerIndex = 1, layerId = "rival",
        )
        val third = place(listOf(visible, rival), previous = second, now = SYMBOL_FADE_DURATION_MS * 2)
        val fading = third.result().symbols.single { it.instance.key == "label" }
        assertEquals(1f, fading.opacity.text.opacity, 1e-4f, "starts fading out from where it was")
        assertEquals(0f, fading.opacity.text.alphaAt(third.result().fadeChangeAt(SYMBOL_FADE_DURATION_MS)))
        assertTrue(third.result().fadeRemainingMs > 0L)
    }

    @Test
    fun `a variable anchor choice is carried over from the previous cycle`() {
        val bucket = SymbolFixtures.bucket(
            listOf(SymbolFixtures.textInstance("label", global = Point(0.5, 0.5)))
        )
        val first = place(listOf(bucket))
        val id = first.result().symbols.single().crossTileID
        first.variableOffsets[id] = 2

        val second = place(listOf(bucket), previous = first)

        assertEquals(2, second.variableOffsets[id], "an anchor that fitted is tried first again")
    }

    @Test
    fun `a symbol whose tile leaves the visible set fades out instead of vanishing`() {
        /* The blink this fixes. A pan or a zoom swaps the bucket set wholesale, and a symbol on a
         * departing tile is no longer among the candidates the fading pass walks -- so it used to
         * disappear in one frame however opaque it still was. Upstream never sees it because
         * `Tile.holdingForSymbolFade` keeps the departing tile in the render set; the symbols are
         * held here instead. */
        val n = 2.0.pow(6)
        val left = SymbolFixtures.bucket(
            listOf(instanceIn("Left", (32 + 0.5) / n, (21 + 0.5) / n, 6, 32, 21)), z = 6, x = 32, y = 21,
        )
        val right = SymbolFixtures.bucket(
            listOf(instanceIn("Right", (33 + 0.5) / n, (21 + 0.5) / n, 6, 33, 21)), z = 6, x = 33, y = 21,
        )
        val viewportInfo = SymbolFixtures.viewport(
            width = 1200, height = 800,
            centroid = Point((32 + 1.0) / n, (21 + 0.5) / n),
            worldPx = (LAYOUT_TILE_SIZE * n).toInt(),
        )

        fun cycle(buckets: List<SymbolBucket>, now: Long, previous: Placement?): Placement {
            index.addLayer(SymbolFixtures.LAYER, buckets, density = 1f)
            val placement = Placement(viewportInfo, zoom = 6.0, collisionDetectionEnabled = true)
            placement.placeBuckets(PlacementOrder(buckets, viewportInfo), previous)
            placement.commit(previous, now)
            placement.result(previous)
            return placement
        }

        val first = cycle(listOf(left, right), now = 0L, previous = null)
        val opaque = cycle(listOf(left, right), now = SYMBOL_FADE_DURATION_MS + 100, previous = first)
        assertEquals(
            1f, opaque.result(first).symbols.single { it.instance.key == "Right" }.opacity.text.opacity,
            "both labels are up",
        )

        // The right-hand tile scrolls out: its bucket is gone from the list entirely.
        val departed = cycle(listOf(left), now = SYMBOL_FADE_DURATION_MS * 2 + 100, previous = opaque)
        val held = departed.result(opaque).symbols.singleOrNull { it.instance.key == "Right" }

        assertNotNull(held, "the departing label is still drawn")
        assertEquals(1f, held.opacity.text.opacity, "and starts its fade from where it was")
        assertFalse(held.opacity.text.placed, "heading for hidden")
        assertEquals(0f, held.opacity.text.alphaAt(departed.result(opaque).fadeChangeAt(SYMBOL_FADE_DURATION_MS)))

        // One fade later it is gone for good.
        val settled = cycle(listOf(left), now = SYMBOL_FADE_DURATION_MS * 3 + 200, previous = departed)
        assertContentEquals(listOf("Left"), settled.result(departed).symbols.map { it.instance.key })
    }
}
