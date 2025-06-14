package ovh.plrapps.mapcompose.maplibre.renderer

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import ovh.plrapps.mapcompose.maplibre.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.maplibre.renderer.collision.CollisionDetector
import ovh.plrapps.mapcompose.maplibre.spec.Tile
import ovh.plrapps.mapcompose.maplibre.spec.style.SymbolLayer

class SymbolsProducer(
    configuration: MapLibreConfiguration,
    private val textMeasurer: TextMeasurer,
) : BaseRenderer(configuration = configuration) {

    val symbolsPainter = SymbolLayerPainter(
        textMeasurer = textMeasurer,
        spriteManager = configuration.spriteManager,
        configuration = configuration
    )

    fun produce(
        tile: Tile?,
        styleLayer: SymbolLayer,
        zoom: Double,
        canvasSize: Int,
        actualZoom: Double,
        tileX: Int = 0,
        tileY: Int = 0,
        density: Density,
    ): List<Symbol> {
        if (!isZoomInRange(styleLayer, zoom)) {
//            println("  missed by zoom")
            return emptyList()
        }

        if (tile == null || tile.layers.isEmpty()) return emptyList()

        val tileLayer = tile.layers.find { it.name == styleLayer.sourceLayer }

        if (tileLayer == null) {
            return emptyList()
        }

        // The sprite and the text of the sprite MAY be in different features, but in the same tile,
        // because their points match. Therefore, this is one element, but in what order they were drawn,
        // we do not know. Therefore, if the points match, then the text should be placed under the sprite, and if it is a sprite, then draw it above the text
        val symbols = mutableListOf<Symbol>()

        for (feature in tileLayer.features) {
            val isShouldRenderFeature = shouldRenderFeature(feature, tileLayer, styleLayer, zoom)
            if (!isShouldRenderFeature) continue

            val featureProperties = extractFeatureProperties(feature, tileLayer)
            val extent = tileLayer.extent ?: 4096

            symbolsPainter.produceSymbol(
                id = feature.id?.toString() ?: "unknown_${feature.hashCode()}",
                feature = feature,
                style = styleLayer,
                canvasSize = canvasSize,
                extent = extent,
                zoom = zoom,
                featureProperties = featureProperties,
                actualZoom = actualZoom,
                tileX = tileX,
                tileY = tileY,
                density = density
            ).let { symbol->
                symbols.addAll(symbol)
            }
        }
        return symbols
    }
}