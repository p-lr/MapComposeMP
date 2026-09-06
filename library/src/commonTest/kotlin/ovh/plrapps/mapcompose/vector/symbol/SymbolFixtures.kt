package ovh.plrapps.mapcompose.vector.symbol

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.serialization.json.Json
import ovh.plrapps.mapcompose.vector.core.VectorRasterizer
import ovh.plrapps.mapcompose.vector.core.ViewportInfo
import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.data.MapLibreTileSource
import ovh.plrapps.mapcompose.vector.data.TileRef
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphManager
import ovh.plrapps.mapcompose.vector.renderer.LabelArt
import ovh.plrapps.mapcompose.vector.renderer.Mvt
import ovh.plrapps.mapcompose.vector.renderer.Point
import ovh.plrapps.mapcompose.vector.renderer.utils.MVTViewport
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.MapLibreStyle
import ovh.plrapps.mapcompose.vector.spec.style.SymbolLayer
import ovh.plrapps.mapcompose.vector.spec.style.symbol.SymbolLayout
import ovh.plrapps.mapcompose.vector.spec.style.symbol.SymbolPaint
import ovh.plrapps.mapcompose.vector.spec.tilejson.TileJson
import ovh.plrapps.mapcompose.vector.utils.obb.OBB
import ovh.plrapps.mapcompose.vector.utils.obb.ObbPoint
import ovh.plrapps.mapcompose.vector.utils.obb.Size as ObbSize
import pbandk.encodeToByteArray

/**
 * Buckets, instances and viewports built from plain numbers, for the tests of the two symbol passes.
 *
 * Nothing here rasterizes, so these live in `commonTest`: an `ImageBitmap` cannot be allocated on
 * `androidHostTest`, which is why the tests that draw sit in `skiaTest` instead.
 */
internal object SymbolFixtures {

    const val LAYER = "test"

    /** A label with a box but no ink. Placement and the cross-tile index read nothing else. */
    class FakeLabel(
        override val text: String,
        override val width: Float = 10f,
        override val height: Float = 10f,
    ) : LabelArt() {
        override fun draw(scope: DrawScope, topLeft: Offset, alpha: Float, scale: Float) = Unit
    }

    fun labelPlacement(
        text: String,
        center: Offset,
        width: Float = 10f,
        height: Float = 10f,
        angle: Float = 0f,
        layerIndex: Int = 0,
        overlapMode: OverlapMode = OverlapMode.Never,
        ignorePlacement: Boolean = false,
        sortKey: Double = 0.0,
    ): LabelPlacement = LabelPlacement(
        text = text,
        position = ObbPoint(center.x, center.y),
        angle = angle,
        bounds = Rect(
            center.x - width / 2f, center.y - height / 2f,
            center.x + width / 2f, center.y + height / 2f,
        ),
        obb = OBB(ObbPoint(center.x, center.y), ObbSize(width, height), angle),
        layerIndex = layerIndex,
        inLayerPriority = sortKey,
        overlapMode = overlapMode,
        ignorePlacement = ignorePlacement,
    )

    /**
     * A text symbol anchored at [global] in normalized Mercator, with a [width] x [height] box.
     *
     * [tileAnchor] only matters to the cross-tile index and to a line label's circle chain; it
     * defaults to the tile's centre.
     */
    fun textInstance(
        key: String,
        global: Point = Point(0.5, 0.5),
        tileAnchor: Offset = Offset(LAYOUT_TILE_SIZE / 2f, LAYOUT_TILE_SIZE / 2f),
        width: Float = 10f,
        height: Float = 10f,
        angle: Float = 0f,
        layerIndex: Int = 0,
        overlapMode: OverlapMode = OverlapMode.Never,
        ignorePlacement: Boolean = false,
        sortKey: Double = 0.0,
        line: List<Pair<Float, Float>>? = null,
        globalLine: List<Point>? = null,
        globalAnchorIndex: Int = 0,
        lineOffsetX: Float = 0f,
        lineOffsetY: Float = 0f,
        keepUpright: Boolean = true,
        layoutSize: Float = 16f,
        featureSizes: FeatureSizes = FeatureSizes(0.0, 0.0),
    ): SymbolInstance.Text {
        val placement = labelPlacement(
            text = key, center = Offset(tileAnchor.x, tileAnchor.y),
            width = width, height = height, angle = angle, layerIndex = layerIndex,
            overlapMode = overlapMode, ignorePlacement = ignorePlacement,
            sortKey = sortKey,
        )
        return SymbolInstance.Text(
            id = key,
            key = key,
            global = global,
            tileAnchor = tileAnchor,
            placement = CompoundLabelPlacement(placement, placement),
            value = FakeLabel(key, width, height),
            line = line,
            globalLine = globalLine,
            globalAnchorIndex = globalAnchorIndex,
            lineOffsetX = lineOffsetX,
            lineOffsetY = lineOffsetY,
            keepUpright = keepUpright,
            layoutSize = layoutSize,
            featureSizes = featureSizes,
        )
    }

    fun bucket(
        instances: List<SymbolInstance>,
        z: Int = 6,
        x: Int = 8,
        y: Int = 8,
        layerIndex: Int = 0,
        layerId: String = LAYER,
        textSizeData: SizeData = SizeData.Constant(16.0),
        iconSizeData: SizeData = SizeData.Constant(1.0),
        /** `1` for an ordinary tile; `2^(display zoom - source maxzoom)` for an overzoomed one. */
        span: Int = 1,
        /** The zoom the bucket is *shown* at, which is above [z] when the source is overzoomed. */
        bucketZoom: Int = z,
        /** `symbol-z-order` and friends, which are layer properties -- see [SymbolOrdering]. */
        ordering: SymbolOrdering = SymbolOrdering.DEFAULT,
    ): SymbolBucket = SymbolBucket(
        ref = TileRef(z = z, x = x, y = y, subX = 0, subY = 0, span = span),
        layerIndex = layerIndex,
        layerId = layerId,
        sourceName = "source",
        zoom = bucketZoom,
        canvasSize = LAYOUT_TILE_SIZE * span,
        textSizeData = textSizeData,
        iconSizeData = iconSizeData,
        instances = instances,
        ordering = ordering,
    )

    /** A layer that orders by [zOrder], with [canOverlap] and a per-feature `symbol-sort-key`. */
    fun ordering(
        zOrder: String = ovh.plrapps.mapcompose.vector.spec.style.SYMBOL_Z_ORDER_AUTO,
        hasSortKey: Boolean = false,
        canOverlap: Boolean = false,
    ): SymbolOrdering = SymbolOrdering(zOrder = zOrder, hasSortKey = hasSortKey, canOverlap = canOverlap)

    /**
     * A viewport centred on [centroid] whose world is exactly [worldPx] wide, so a normalized
     * Mercator delta of `d` projects to `d * worldPx` screen pixels.
     */
    fun viewport(
        width: Int = 800,
        height: Int = 600,
        centroid: Point = Point(0.5, 0.5),
        worldPx: Int = 1000,
        angleRad: Float = 0f,
        zoom: Int = 6,
        fractionalZoom: Double = 6.0,
    ): ViewportInfo = ViewportInfo(
        matrix = emptyMap(),
        size = IntSize(width, height),
        angleRad = angleRad,
        pitch = 0f,
        zoom = zoom,
        fractionalZoom = fractionalZoom,
        centroidX = centroid.x,
        centroidY = centroid.y,
        scale = 1.0,
        fullWidth = worldPx,
        fullHeight = worldPx,
    )

    // region a real rasterizer, for the tests of the two passes end to end

    /** One MVT tile with a single point feature in a `places` layer. */
    fun tileBytes(): ByteArray = Tile(
        layers = listOf(
            Tile.Layer(
                version = 2,
                name = "places",
                features = listOf(Mvt.pointFeature(2048 to 2048)),
                extent = 4096,
            )
        )
    ).encodeToByteArray()

    /**
     * One tile whose only feature sits wherever [world] falls inside it, or no feature at all when
     * that point is in another tile. The same conceptual label at every zoom, which is what the
     * cross-tile index has to recognise across a zoom step.
     */
    fun tileBytesAt(world: Point, z: Int, x: Int, y: Int): ByteArray {
        val tiles = 1 shl z
        val localX = (world.x * tiles - x) * Mvt.DEFAULT_EXTENT
        val localY = (world.y * tiles - y) * Mvt.DEFAULT_EXTENT
        val inside = localX >= 0 && localX < Mvt.DEFAULT_EXTENT && localY >= 0 && localY < Mvt.DEFAULT_EXTENT
        return Tile(
            layers = listOf(
                Tile.Layer(
                    version = 2,
                    name = "places",
                    features = if (inside) {
                        listOf(Mvt.pointFeature(localX.toInt() to localY.toInt()))
                    } else emptyList(),
                    extent = Mvt.DEFAULT_EXTENT,
                )
            )
        ).encodeToByteArray()
    }

    /** A rasterizer over one symbol layer reading one vector source, serving [bytes] for every tile. */
    fun rasterizer(
        bytes: ByteArray = tileBytes(),
        onFetch: suspend () -> Unit = {},
        bytesFor: (z: Int, x: Int, y: Int) -> ByteArray = { _, _, _ -> bytes },
        /**
         * Without one there is no [ovh.plrapps.mapcompose.vector.renderer.LabelArt] and so no
         * instance at all, because `commonTest` has neither a font resolver for the `TextMeasurer`
         * fallback nor a graphics backend to rasterize glyphs on -- which is why a test that needs
         * the layout pass to produce real symbols lives in `skiaTest` and passes one in.
         */
        glyphManager: GlyphManager? = null,
        /** The symbol layer's `layout`, so a test can name the font stack [glyphManager] serves. */
        layoutJson: String = """{"text-field":"A"}""",
    ): VectorRasterizer {
        val json = Json { ignoreUnknownKeys = true }
        val layer = SymbolLayer(
            id = "labels",
            source = "vec",
            sourceLayer = "places",
            layout = json.decodeFromString(SymbolLayout.serializer(), layoutJson),
            /* Not optional: `SymbolLayerLayout.produceSymbol` bails on a layer with no `paint`. */
            paint = json.decodeFromString(SymbolPaint.serializer(), """{}"""),
        )
        val configuration = MapLibreConfiguration(
            style = MapLibreStyle(layers = listOf(layer)),
            tileSources = mapOf(
                "vec" to MapLibreTileSource(
                    tileJson = TileJson(
                        tilejson = "2.2.0",
                        tiles = listOf("test://{z}/{x}/{y}.pbf"),
                        minzoom = 0,
                        maxzoom = 14,
                    ),
                )
            ),
            spriteManager = null,
            glyphManager = glyphManager,
        )
        return VectorRasterizer(
            configuration = configuration,
            densityState = MutableStateFlow(Density(1f)),
            fontFamilyResolverState = MutableStateFlow<FontFamily.Resolver?>(null),
            textMeasurerState = MutableStateFlow<TextMeasurer?>(null),
            getTileStream = { _, row, col, zoomLvl ->
                onFetch()
                Buffer().apply { write(bytesFor(zoomLvl, col, row)) } as RawSource
            },
        )
    }

    /** One visible tile, which the layout pass expands by one in each direction into a 3x3 block. */
    fun mvtViewport(zoom: Float) = MVTViewport(
        width = 512f,
        height = 512f,
        bearing = 0f,
        pitch = 0f,
        zoom = zoom,
        tileMatrix = mapOf(4 to 4..4),
    )

    // endregion
}
