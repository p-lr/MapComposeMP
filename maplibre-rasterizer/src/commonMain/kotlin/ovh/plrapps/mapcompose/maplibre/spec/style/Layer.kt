package ovh.plrapps.mapcompose.maplibre.spec.style

import ovh.plrapps.mapcompose.maplibre.spec.style.background.BackgroundLayout
import ovh.plrapps.mapcompose.maplibre.spec.style.background.BackgroundPaint
import ovh.plrapps.mapcompose.maplibre.spec.style.circle.CircleLayout
import ovh.plrapps.mapcompose.maplibre.spec.style.circle.CirclePaint
import ovh.plrapps.mapcompose.maplibre.spec.style.fill.FillLayout
import ovh.plrapps.mapcompose.maplibre.spec.style.fill.FillPaint
import ovh.plrapps.mapcompose.maplibre.spec.style.fillExtrusion.FillExtrusionLayout
import ovh.plrapps.mapcompose.maplibre.spec.style.fillExtrusion.FillExtrusionPaint
import ovh.plrapps.mapcompose.maplibre.spec.style.heatmap.HeatmapLayout
import ovh.plrapps.mapcompose.maplibre.spec.style.heatmap.HeatmapPaint
import ovh.plrapps.mapcompose.maplibre.spec.style.hillshade.HillshadeLayout
import ovh.plrapps.mapcompose.maplibre.spec.style.hillshade.HillshadePaint
import ovh.plrapps.mapcompose.maplibre.spec.style.line.LineLayout
import ovh.plrapps.mapcompose.maplibre.spec.style.line.LinePaint
import ovh.plrapps.mapcompose.maplibre.spec.style.raster.RasterLayout
import ovh.plrapps.mapcompose.maplibre.spec.style.raster.RasterPaint
import ovh.plrapps.mapcompose.maplibre.spec.style.sky.SkyLayout
import ovh.plrapps.mapcompose.maplibre.spec.style.sky.SkyPaint
import ovh.plrapps.mapcompose.maplibre.spec.style.symbol.SymbolLayout
import ovh.plrapps.mapcompose.maplibre.spec.style.symbol.SymbolPaint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValue

@JsonClassDiscriminator("type")
@Serializable
sealed class Layer {
    abstract val id: String
    abstract val type: String
    abstract val source: String?
    @SerialName("source-layer")
    abstract val sourceLayer: String?
    abstract val filter: Filter?
    abstract val minzoom: Double?
    abstract val maxzoom: Double?
    abstract val layout: LayoutInterface?
    abstract val paint: PaintInterface?
}

@Serializable
@SerialName("line")
data class LineLayer(
    override val id: String,
    override val type: String = "line",
    override val source: String? = null,
    @SerialName("source-layer")
    override val sourceLayer: String? = null,
    override val filter: Filter? = null,
    override val minzoom: Double? = null,
    override val maxzoom: Double? = null,
    override val layout: LineLayout? = null,
    override val paint: LinePaint? = null,
) : Layer()

@Serializable
@SerialName("fill")
data class FillLayer(
    override val id: String,
    override val type: String = "fill",
    override val source: String? = null,
    @SerialName("source-layer")
    override val sourceLayer: String? = null,
    override val filter: Filter? = null,
    override val minzoom: Double? = null,
    override val maxzoom: Double? = null,
    override val layout: FillLayout? = null,
    override val paint: FillPaint? = null,
) : Layer()

@Serializable
@SerialName("symbol")
data class SymbolLayer(
    override val id: String,
    override val type: String = "symbol",
    override val source: String? = null,
    @SerialName("source-layer")
    override val sourceLayer: String? = null,
    override val filter: Filter? = null,
    override val minzoom: Double? = null,
    override val maxzoom: Double? = null,
    override val layout: SymbolLayout? = null,
    override val paint: SymbolPaint? = null,
) : Layer()

@Serializable
@SerialName("circle")
data class CircleLayer(
    override val id: String,
    override val type: String = "circle",
    override val source: String? = null,
    @SerialName("source-layer")
    override val sourceLayer: String? = null,
    override val filter: Filter? = null,
    override val minzoom: Double? = null,
    override val maxzoom: Double? = null,
    override val layout: CircleLayout? = null,
    override val paint: CirclePaint? = null,
) : Layer()

@Serializable
@SerialName("background")
data class BackgroundLayer(
    override val id: String,
    override val type: String = "background",
    override val source: String? = null,
    @SerialName("source-layer")
    override val sourceLayer: String? = null,
    override val filter: Filter? = null,
    override val minzoom: Double? = null,
    override val maxzoom: Double? = null,
    override val layout: BackgroundLayout? = null,
    override val paint: BackgroundPaint? = null,
) : Layer()

@Serializable
@SerialName("raster")
data class RasterLayer(
    override val id: String,
    override val type: String = "raster",
    override val source: String? = null,
    @SerialName("source-layer")
    override val sourceLayer: String? = null,
    override val filter: Filter? = null,
    override val minzoom: Double? = null,
    override val maxzoom: Double? = null,
    override val layout: RasterLayout? = null,
    override val paint: RasterPaint? = null,
) : Layer()

@Serializable
@SerialName("hillshade")
data class HillshadeLayer(
    override val id: String,
    override val type: String = "hillshade",
    override val source: String? = null,
    @SerialName("source-layer")
    override val sourceLayer: String? = null,
    override val filter: Filter? = null,
    override val minzoom: Double? = null,
    override val maxzoom: Double? = null,
    override val layout: HillshadeLayout? = null,
    override val paint: HillshadePaint? = null,
) : Layer()

@Serializable
@SerialName("heatmap")
data class HeatmapLayer(
    override val id: String,
    override val type: String = "heatmap",
    override val source: String? = null,
    @SerialName("source-layer")
    override val sourceLayer: String? = null,
    override val filter: Filter? = null,
    override val minzoom: Double? = null,
    override val maxzoom: Double? = null,
    override val layout: HeatmapLayout? = null,
    override val paint: HeatmapPaint? = null,
) : Layer()

@Serializable
@SerialName("fill-extrusion")
data class FillExtrusionLayer(
    override val id: String,
    override val type: String = "fill-extrusion",
    override val source: String? = null,
    @SerialName("source-layer")
    override val sourceLayer: String? = null,
    override val filter: Filter? = null,
    override val layout: FillExtrusionLayout? = null,
    override val paint: FillExtrusionPaint? = null,
    override val minzoom: Double? = null,
    override val maxzoom: Double? = null
) : Layer()

@Serializable
@SerialName("sky")
data class SkyLayer(
    override val id: String,
    override val type: String = "sky",
    override val source: String? = null,
    @SerialName("source-layer")
    override val sourceLayer: String? = null,
    override val filter: Filter? = null,
    override val minzoom: Double? = null,
    override val maxzoom: Double? = null,
    override val layout: SkyLayout? = null,
    override val paint: SkyPaint? = null,
) : Layer()

