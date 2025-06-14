package ovh.plrapps.mapcompose.maplibre.ui.mapcompose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlin.math.abs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import mapcompose_mp.demo.maplibredebugapp.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.skia.EncodedImageFormat
import ovh.plrapps.mapcompose.api.*
import ovh.plrapps.mapcompose.core.TileStreamProvider
import ovh.plrapps.mapcompose.core.ViewportInfo
import ovh.plrapps.mapcompose.maplibre.MapLibreRasterizer
import ovh.plrapps.mapcompose.maplibre.cache.FileTileCache
import ovh.plrapps.mapcompose.maplibre.data.getMapLibreConfiguration
import ovh.plrapps.mapcompose.maplibre.renderer.CompoundLabelPlacement
import ovh.plrapps.mapcompose.maplibre.renderer.Symbol
import ovh.plrapps.mapcompose.maplibre.renderer.collision.CollisionDetector
import ovh.plrapps.mapcompose.maplibre.renderer.collision.LabelPlacement
import ovh.plrapps.mapcompose.maplibre.util.toMVTViewport
import ovh.plrapps.mapcompose.maplibre.utils.obb.OBB
import ovh.plrapps.mapcompose.maplibre.utils.obb.ObbPoint
import ovh.plrapps.mapcompose.ui.layout.Fit
import ovh.plrapps.mapcompose.ui.state.MapState
import ovh.plrapps.mapcompose.utils.throttle
import kotlin.math.pow
import kotlin.math.roundToInt
import org.jetbrains.skia.Image as SkiaImage

class MapComposeEngineViewModel(
    val density: Density,
    val fontFamilyResolver: FontFamily.Resolver,
    val textMeasurer: TextMeasurer,
    val initialViewPort: Dp,
) : ViewModel() {
    val zoom = MutableStateFlow(0.0)
    val symbols = MutableStateFlow<List<Symbol>>(emptyList())
    val symbolsBitmap = MutableStateFlow<ImageBitmap?>(null)
    var collisionLayerIsVisible = MutableStateFlow(false)


    private var viewPortSizePx: Float = with(density) { initialViewPort.toPx() }
    private val maxLevel = 20
    private val minLevel = 0
    private val tilePx = with(density) { 512.dp.toPx() }.toInt()
    private val mapSize = mapSizeAtLevel(maxLevel, tileSize = tilePx)

    private val tileStreamProvider = object : TileStreamProvider {
        override suspend fun getTileStream(
            row: Int,
            col: Int,
            zoomLvl: Int
        ): RawSource? {
            val rasterizer = getRasterizer()

            val imageBitmap = rasterizer.getTile(
                x = col,
                y = row,
                zoom = zoomLvl.toDouble(),
                tileSize = tilePx
            )
            val skiaBmp = imageBitmap.asSkiaBitmap()

            val bytes = SkiaImage
                .makeFromBitmap(skiaBmp)
                .encodeToData(EncodedImageFormat.PNG)
                ?.bytes
                ?: return null

            return Buffer().apply {
                write(bytes)
            }

        }
    }

    val state = MapState(
        levelCount = maxLevel + 1,
        fullWidth = mapSize,
        fullHeight = mapSize,
        tileSize = tilePx,
        workerCount = 4
    ) {
        minimumScaleMode(Fit)
        // 15.33/60.00125/29.76867 - Kotlin isl.
        val p = latLngToNormalizedPoint(
            lat = 59.990776071439,
            lng = 29.768192029815395
        )
        scroll(p.x, p.y)
    }.apply {
        addLayer(tileStreamProvider)
        scale = 1.0 / 2.0.pow(maxLevel - 14.33)
    }.apply {
        viewportInfoFlow
            .mapLatest { viewportInfo ->
                zoom.value = viewportInfo?.zoom ?: 0.0
            }
            .launchIn(scope = viewModelScope)

        viewportInfoFlow
            .combine(collisionLayerIsVisible) { vp, flag ->
                Pair(vp, flag)
            }
            .throttle(250)
            .map { (viewportInfo, collisionLayerIsVisible) ->
                viewportInfo ?: return@map

                val zoomLvl = viewportInfo.zoom // Используем zoom из ViewportInfo!
                getRasterizer().let { rasterizer ->
                    val nextSymbols = getRasterizer().produceSymbols(
                        viewport = viewportInfo.toMVTViewport(),
                        tileSize = tileSize,
                        z = zoomLvl
                    ).getOrElse { e ->
                        println("[ERROR] produceSymbols(): ${e.message}")
                        return@map
                    }

                    if (collisionLayerIsVisible) {
                        // Create a debug ImageBitmap with symbols
                        createSymbolsBitmap(nextSymbols, viewportInfo)
                    }

                    updateSymbols(
                        nextSymbols = if (rasterizer.configuration.collisionDetectionEnabled) {
                            clearCollision(nextSymbols, viewportInfo)
                        } else {
                            nextSymbols
                        }
                    )
                }
            }
            .catch {
                println("error: ${it.message}")
            }
            .launchIn(scope = viewModelScope)
    }

    private var prevSymbols = emptyList<Symbol>()

    private fun updateSymbols(nextSymbols: List<Symbol>) {
        println("updateSymbols ${nextSymbols.size}")
        checkIndexErrors(nextSymbols)

        val nextSymbolsId = nextSymbols.map { it.id }
        val prevSymbolsId = prevSymbols.map { it.id }

        // We remove markers that no longer exist
        prevSymbolsId.forEach { prevSymbolId ->
            val shouldRemove = prevSymbolId !in nextSymbolsId
            if (shouldRemove) {
                state.removeMarker(prevSymbolId)
            }
        }

        // Adding new markers
        for (symbol in nextSymbols) {
            if (symbol.id in prevSymbolsId) continue

            state.addMarker(
                id = symbol.id,
                x = symbol.global.x,
                y = symbol.global.y,
                relativeOffset = symbol.align,
                clickable = false
            ) {
                symbol.render()
            }
        }
        prevSymbols = nextSymbols
        symbols.value = nextSymbols
    }

    private class Counter {
        var value: Int = 0
    }

    private fun checkIndexErrors(nextSymbols: List<Symbol>) {
        val counters = mutableMapOf<String, Counter>()

        nextSymbols.forEach { symbol ->
            counters.getOrPut(symbol.id) { Counter() }.value++
        }
        counters.forEach { (id, value) ->
            if (value.value > 1) {
                println("[ERROR]: Id $id not unique (${value.value})")
            }
        }
    }

    /**
     * Simple conversion from normalized Mercator coordinates to viewport coordinates
     * Using map centroid and current zoom
     */
    private fun mercatorToViewport(
        mercatorX: Double,
        mercatorY: Double,
        viewportInfo: ViewportInfo
    ): Offset {
        // We get the centroid (the center of the visible area) of the map
        val centroidX = state.centroidX
        val centroidY = state.centroidY
        val currentScale = state.scale

        // Calculate the relative offset of the symbol from the center of the map
        val deltaX = mercatorX - centroidX
        val deltaY = mercatorY - centroidY

        // Converting viewport to pixels taking into account scale
        val viewportCenterX = viewportInfo.size.width.toDouble() / 2.0
        val viewportCenterY = viewportInfo.size.height.toDouble() / 2.0

        val viewportX = viewportCenterX + deltaX * state.fullSize.width.toDouble() * currentScale
        val viewportY = viewportCenterY + deltaY * state.fullSize.height.toDouble() * currentScale

        return Offset(viewportX.toFloat(), viewportY.toFloat())
    }

    /**
     * Detects collisions and determines whether the element can be placed or not.
     */
    private fun clearCollision(symbols: List<Symbol>, viewportInfo: ViewportInfo): List<Symbol> {
        val collisionDetector = CollisionDetector()
        val acceptedSymbols = mutableListOf<Symbol>()

        // We iterate through the symbols in the same way as in createSymbolsBitmap
        symbols.forEach { symbol ->
            val viewportPos = mercatorToViewport(
                mercatorX = symbol.global.x,
                mercatorY = symbol.global.y,
                viewportInfo = viewportInfo
            )

            // Get dimensions and rotation angle from labelPlacement
            val spritePlacement = symbol.placement.spritePlacement
            val textPlacement = symbol.placement.textPlacement

            // Check for collisions depending on the symbol type (just like in createSymbolsBitmap)
            when (symbol) {
                is Symbol.SpriteWithText -> {
                    // For SpriteWithText we use the SAME LOGIC as in createSymbolsBitmap

                    // 1. Create a LabelPlacement for the sprite in viewport coordinates
                    val spriteViewportPlacement = createViewportLabelPlacement(
                        center = viewportPos,
                        originalPlacement = spritePlacement
                    )

                    // 2. Calculate the text position EXACTLY the same way as in createSymbolsBitmap
                    val textViewportPlacement = textPlacement?.let { textPlace ->
                        val spriteHeight = symbol.spriteSize.height.toFloat()
                        val verticalGap = symbol.verticalGap
                        val textHeight = symbol.textSize.height.toFloat()

                        // Move text down from sprite center
                        val textOffsetY = spriteHeight / 2f + verticalGap + textHeight / 2f
                        val textViewportPos = Offset(
                            viewportPos.x,
                            viewportPos.y + textOffsetY
                        )

                        createViewportLabelPlacement(
                            center = textViewportPos,
                            originalPlacement = textPlace
                        )
                    }

                    // Checking if a sprite can be placed
                    val spriteCanPlace = !collisionDetector.wouldCollide(spriteViewportPlacement)

                    // Checking if text can be placed
                    val textCanPlace = textViewportPlacement?.let {
                        !collisionDetector.wouldCollide(it)
                    } ?: true

                    // We make a decision on placement - ONLY if we can accommodate FULLY
                    if (spriteCanPlace && textCanPlace) {
                        // We can place the full symbol
                        collisionDetector.tryPlaceLabel(spriteViewportPlacement)
                        textViewportPlacement?.let { collisionDetector.tryPlaceLabel(it) }
                        acceptedSymbols.add(symbol)
                    } else if (spriteCanPlace && !textCanPlace) {
                        collisionDetector.tryPlaceLabel(spriteViewportPlacement)
                        acceptedSymbols.add(
                            Symbol.Sprite(
                                id = symbol.id,
                                global = symbol.global,
                                placement = CompoundLabelPlacement(spritePlacement, spritePlacement),
                                value = symbol.sprite
                            )
                        )
                    }
                }

                is Symbol.Sprite -> {
                    // For regular sprites we use the SAME LOGIC as in createSymbolsBitmap
                    val spriteViewportPlacement = createViewportLabelPlacement(
                        center = viewportPos,
                        originalPlacement = spritePlacement
                    )

                    val textViewportPlacement = textPlacement?.let { textPlace ->
                        createViewportLabelPlacement(
                            center = viewportPos, // For Sprite text in the same position
                            originalPlacement = textPlace
                        )
                    }

                    val spriteCanPlace = !collisionDetector.wouldCollide(spriteViewportPlacement)
                    val textCanPlace = textViewportPlacement?.let {
                        !collisionDetector.wouldCollide(it)
                    } ?: true

                    // We accept the symbol only if both the sprite and the text can be placed.
                    if (spriteCanPlace && textCanPlace) {
                        collisionDetector.tryPlaceLabel(spriteViewportPlacement)
                        textViewportPlacement?.let { collisionDetector.tryPlaceLabel(it) }
                        acceptedSymbols.add(symbol)
                    }
                }

                is Symbol.Text -> {
                    // For text we use the SAME LOGIC as in createSymbolsBitmap
                    val textViewportPlacement = textPlacement?.let { textPlace ->
                        createViewportLabelPlacement(
                            center = viewportPos,
                            originalPlacement = textPlace
                        )
                    }

                    val textCanPlace = textViewportPlacement?.let {
                        !collisionDetector.wouldCollide(it)
                    } ?: false

                    if (textCanPlace) {
                        collisionDetector.tryPlaceLabel(textViewportPlacement)
                        acceptedSymbols.add(symbol)
                    }
                }
            }
        }

        return acceptedSymbols
    }

    /**
     * Creates a LabelPlacement with viewport coordinates using the same logic as drawSymbolDebugRect
     */
    private fun createViewportLabelPlacement(
        center: Offset,
        originalPlacement: LabelPlacement
    ): LabelPlacement {
        val bounds = originalPlacement.bounds
        val angle = originalPlacement.angle

        // The dimensions remain the same
        val rectWidth = bounds.width
        val rectHeight = bounds.height

        // New bounds relative to viewport center
        val newBounds = Rect(
            left = center.x - rectWidth / 2f,
            top = center.y - rectHeight / 2f,
            right = center.x + rectWidth / 2f,
            bottom = center.y + rectHeight / 2f
        )

        return originalPlacement.copy(
            position = ObbPoint(center.x, center.y),
            bounds = newBounds,
            obb = OBB(
                center = ObbPoint(center.x, center.y),
                size = ovh.plrapps.mapcompose.maplibre.utils.obb.Size(rectWidth, rectHeight),
                rotation = angle
            )
        )
    }

    /**
     * Creates a debug ImageBitmap with symbols to render
     */
    private fun createSymbolsBitmap(symbols: List<Symbol>, viewportInfo: ViewportInfo) {
        if (symbols.isEmpty()) {
            symbolsBitmap.value = null
            return
        }

        val width = viewportInfo.size.width
        val height = viewportInfo.size.height

        println("DEBUG: Creating symbols bitmap ${width}x${height} for ${symbols.size} symbols")

        val bitmap = ImageBitmap(width, height)
        val canvas = Canvas(bitmap)
        val drawScope = CanvasDrawScope()

        // Clear the canvas with transparent color
        drawScope.draw(
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            canvas = canvas,
            size = Size(width.toFloat(), height.toFloat())
        ) {
            // Draw debug rectangles for each symbol
            symbols.forEach { symbol ->
                val viewportPos = mercatorToViewport(
                    symbol.global.x,
                    symbol.global.y,
                    viewportInfo
                )

                // Get dimensions and rotation angle from labelPlacement
                val spritePlacement = symbol.placement.spritePlacement
                val textPlacement = symbol.placement.textPlacement

                // Draw debug information depending on the symbol type
                when (symbol) {
                    is Symbol.SpriteWithText -> {
                        // For SpriteWithText you need to draw the sprite and text in their real positions

                        // The sprite is located at symbol.global position (the center of the sprite)
                        drawSymbolDebugRect(
                            center = viewportPos,
                            placement = spritePlacement,
                            color = Color(0xffff6c00), // Orange for sprite
                            strokeWidth = 3f
                        )

                        // The text is below the sprite.
                        textPlacement?.let { textPlace ->
                            // Calculate the position of the text relative to the sprite
                            val spriteHeight = symbol.spriteSize.height.toFloat()
                            val verticalGap = symbol.verticalGap
                            val textHeight = symbol.textSize.height.toFloat()

                            // Move text down from sprite center
                            val textOffsetY = spriteHeight / 2f + verticalGap + textHeight / 2f

                            val textViewportPos = Offset(
                                viewportPos.x,
                                viewportPos.y + textOffsetY
                            )

                            drawSymbolDebugRect(
                                center = textViewportPos,
                                placement = textPlace,
                                color = Color(0xff000000), // Black for text
                                strokeWidth = 2f
                            )
                        }
                    }

                    is Symbol.Sprite -> {
                        // For regular symbols (Sprite, Text) we draw as usual
                        drawSymbolDebugRect(
                            center = viewportPos,
                            placement = spritePlacement,
                            color = Color(0xff8B4513), // Brown
                            strokeWidth = 2f
                        )

                        // Text (if any) in the same position
                        textPlacement?.let { textPlace ->
                            drawSymbolDebugRect(
                                center = viewportPos,
                                placement = textPlace,
                                color = Color(0xff654321),
                                strokeWidth = 1.5f
                            )
                        }
                    }

                    is Symbol.Text -> {
                        textPlacement?.let { textPlace ->
                            drawSymbolDebugRect(
                                center = viewportPos,
                                placement = textPlace,
                                color = Color(0xff46ffcf),
                                strokeWidth = 1.5f
                            )
                        }
                    }
                }

                // Draw the center point of the symbol (global position)
                drawCircle(
                    color = Color(0xff00ff00),
                    radius = 3f,
                    center = viewportPos
                )
            }
        }

        symbolsBitmap.value = bitmap
        println("DEBUG: Symbols bitmap created successfully")
    }

    /**
     * Helper function for drawing debug rectangle with rotation
     */
    private fun DrawScope.drawSymbolDebugRect(
        center: Offset,
        placement: LabelPlacement,
        color: Color,
        strokeWidth: Float
    ) {
        val bounds = placement.bounds
        val angle = placement.angle

        // Dimensions of a rectangle
        val rectWidth = bounds.width
        val rectHeight = bounds.height

        // Position of the upper left corner relative to the center
        val topLeft = Offset(
            center.x - rectWidth / 2f,
            center.y - rectHeight / 2f
        )

        // Draw a rotated rectangle
        rotate(angle, pivot = center) {
            drawRect(
                color = color,
                topLeft = topLeft,
                size = Size(rectWidth, rectHeight),
                style = Stroke(width = strokeWidth)
            )
        }
    }

    /**
     * wmts level are 0 based.
     * At level 0, the map corresponds to just one tile.
     */
    private fun mapSizeAtLevel(wmtsLevel: Int, tileSize: Int): Int {
        return tileSize * 2.0.pow(wmtsLevel).toInt()
    }

    private val iniRasterMutex = Mutex()
    private var tileRasterizer: MapLibreRasterizer? = null

    private suspend fun getRasterizer(): MapLibreRasterizer {
        val mTileRasterizer = tileRasterizer
        if (mTileRasterizer != null) return mTileRasterizer

        return iniRasterMutex.withLock {
            tileRasterizer ?: initRasterizer().also { tileRasterizer = it }
        }
    }

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun initRasterizer(): MapLibreRasterizer {
        val style = Res.readBytes("files/style_street_v2.json").decodeToString()
        val configuration =
            getMapLibreConfiguration(style = style, pixelRatio = density.density.roundToInt()).getOrThrow()
        return MapLibreRasterizer(
            configuration = configuration,
            density = density,
            fontFamilyResolver = fontFamilyResolver,
            textMeasurer = textMeasurer,
            tileCache = FileTileCache("./cache")
        )
    }

    fun updateTileSizeForScreen(minScreen: Dp) {
        viewPortSizePx = with(density) { minScreen.toPx() }
    }

    fun setCollisionLayerIsVisible(it: Boolean) {
        collisionLayerIsVisible.value = it
    }
}