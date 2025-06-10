package ovh.plrapps.mapcompose.maplibre.ui.mapcompose

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import mapcompose_mp.demo.maplibredebugapp.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.skia.EncodedImageFormat
import ovh.plrapps.mapcompose.api.addLayer
import ovh.plrapps.mapcompose.api.scale
import ovh.plrapps.mapcompose.core.TileStreamProvider
import ovh.plrapps.mapcompose.core.ViewportInfo
import ovh.plrapps.mapcompose.maplibre.MapboxRasterizer
import ovh.plrapps.mapcompose.maplibre.cache.FileTileCache
import ovh.plrapps.mapcompose.maplibre.data.getMapLibreConfiguration
import ovh.plrapps.mapcompose.maplibre.renderer.utils.MVTViewport
import ovh.plrapps.mapcompose.ui.layout.Fit
import ovh.plrapps.mapcompose.ui.state.MapState
import kotlin.math.*
import org.jetbrains.skia.Image as SkiaImage

private fun Double.toRadians() = this * PI / 180.0

class MapComposeEngineViewModel(
    val density: Density,
    val fontFamilyResolver: FontFamily.Resolver,
    val textMeasurer: TextMeasurer,
    val initialViewPort: Dp,

    ) : ViewModel() {
    val zoom = MutableStateFlow(0.0)
    private var tileRasterizer: MapboxRasterizer? = null
    private var viewPortSizePx: Float = with(density) { initialViewPort.toPx() }
    private val maxLevel = 20
    private val minLevel = 0
    private val tilePx = with(density) { 512.dp.toPx() }.toInt()
    private val mapSize = mapSizeAtLevel(maxLevel, tileSize = tilePx)

    private val iniRaster = Mutex()

    private val tileStreamProvider = object : TileStreamProvider {
        override suspend fun getTileStream(
            row: Int,
            col: Int,
            zoomLvl: Int,
            viewportInfo: ViewportInfo,
        ): RawSource? {
            zoom.value = zoomLvl.toDouble()
            iniRaster.withLock {
                if (tileRasterizer == null) {
                    tileRasterizer = getRasterizer()
                }
            }
            val rasterizer = tileRasterizer ?: return null

            val imageBitmap = rasterizer.getTile(
                x = col,
                y = row,
                zoom = zoomLvl.toDouble(),
                tileSize = tilePx,
                viewport = MVTViewport(
                    width = (viewportInfo.size.width).toFloat(),
                    height = (viewportInfo.size.height).toFloat(),
                    bearing = viewportInfo.angleRad,
                    pitch = viewportInfo.pitch,
                    zoom = zoomLvl.toFloat(),
                    tileMatrix = viewportInfo.matrix
                ),
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
        workerCount = 1
    ) {
        minimumScaleMode(Fit)
        // 15.33/60.00125/29.76867 - Kotlin isl.
        val lat = 59.990776071439
        val lon = 29.768192029815395

        val x = (lon + 180.0) / 360.0
        val latRad = lat.toRadians()
        val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0
        scroll(x, y)
    }.apply {
        addLayer(tileStreamProvider)
        scale = 1.0 / 2.0.pow(maxLevel - 14.33)
    }

    /**
     * wmts level are 0 based.
     * At level 0, the map corresponds to just one tile.
     */
    private fun mapSizeAtLevel(wmtsLevel: Int, tileSize: Int): Int {
        return tileSize * 2.0.pow(wmtsLevel).toInt()
    }

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun getRasterizer(): MapboxRasterizer {
        val style = Res.readBytes("files/style_street_v2.json").decodeToString()
        val configuration =
            getMapLibreConfiguration(style = style, pixelRatio = density.density.roundToInt()).getOrThrow()
        return MapboxRasterizer(
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
}