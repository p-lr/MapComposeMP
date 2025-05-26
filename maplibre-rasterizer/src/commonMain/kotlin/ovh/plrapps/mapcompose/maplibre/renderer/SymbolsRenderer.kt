package ovh.plrapps.mapcompose.maplibre.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import ovh.plrapps.mapcompose.maplibre.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.maplibre.renderer.collision.CollisionDetector
import ovh.plrapps.mapcompose.maplibre.spec.Tile
import ovh.plrapps.mapcompose.maplibre.spec.style.SymbolLayer

class SymbolsRenderer(
    configuration: MapLibreConfiguration,
    private val textMeasurer: TextMeasurer,
) : BaseRenderer(configuration = configuration) {

    val symbolsPainter = SymbolLayerPainter(
        textMeasurer = textMeasurer,
        spriteManager = configuration.spriteManager,
        configuration = configuration
    )

    fun render(
        drawScope: DrawScope,
        tile: Tile?,
        styleLayer: SymbolLayer,
        collisionDetector: CollisionDetector,
        zoom: Double,
        canvasSize: Int,
        actualZoom: Double,
        offsetInViewport: Offset
    ) {
        if (!isZoomInRange(styleLayer, zoom)) {
//            println("  missed by zoom")
            return
        }

        if (tile == null || tile.layers.isEmpty()) return

        val tileLayer = tile.layers.find { it.name == styleLayer.sourceLayer }

        if (tileLayer == null) {
            return
        }

        // The sprite and the text of the sprite MAY be in different features, but in the same tile,
        // because their points match. Therefore, this is one element, but in what order they were drawn,
        // we do not know. Therefore, if the points match, then the text should be placed under the sprite, and if it is a sprite, then draw it above the text
        val drawnElements = mutableMapOf<Point, DrawnElement>()

        for (feature in tileLayer.features) {
            val isShouldRenderFeature = shouldRenderFeature(feature, tileLayer, styleLayer, zoom)
            if (!isShouldRenderFeature) continue

            val featureProperties = extractFeatureProperties(feature, tileLayer)
            val extent = tileLayer.extent ?: 4096

            symbolsPainter.paint(
                canvas = drawScope,
                drawnElements = drawnElements,
                collisionDetector = collisionDetector,
                feature = feature,
                style = styleLayer,
                canvasSize = canvasSize,
                extent = extent,
                zoom = zoom,
                featureProperties = featureProperties,
                actualZoom = actualZoom,
                offsetInViewport = offsetInViewport,
            )
        }
    }
}