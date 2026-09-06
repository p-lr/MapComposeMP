package ovh.plrapps.mapcompose.vector.spec.style

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import ovh.plrapps.mapcompose.vector.spec.style.serializers.FeatureFilterSerializer
import ovh.plrapps.mapcompose.vector.spec.style.serializers.FilterHolder
import ovh.plrapps.mapcompose.vector.spec.style.background.BackgroundLayout
import ovh.plrapps.mapcompose.vector.spec.style.background.BackgroundPaint
import ovh.plrapps.mapcompose.vector.spec.style.circle.CircleLayout
import ovh.plrapps.mapcompose.vector.spec.style.circle.CirclePaint
import ovh.plrapps.mapcompose.vector.spec.style.fill.FillLayout
import ovh.plrapps.mapcompose.vector.spec.style.fill.FillPaint
import ovh.plrapps.mapcompose.vector.spec.style.fillExtrusion.FillExtrusionLayout
import ovh.plrapps.mapcompose.vector.spec.style.fillExtrusion.FillExtrusionPaint
import ovh.plrapps.mapcompose.vector.spec.style.heatmap.HeatmapLayout
import ovh.plrapps.mapcompose.vector.spec.style.heatmap.HeatmapPaint
import ovh.plrapps.mapcompose.vector.spec.style.hillshade.HillshadeLayout
import ovh.plrapps.mapcompose.vector.spec.style.hillshade.HillshadePaint
import ovh.plrapps.mapcompose.vector.spec.style.line.LineLayout
import ovh.plrapps.mapcompose.vector.spec.style.line.LinePaint
import ovh.plrapps.mapcompose.vector.spec.style.raster.RasterLayout
import ovh.plrapps.mapcompose.vector.spec.style.raster.RasterPaint
import ovh.plrapps.mapcompose.vector.spec.style.sky.SkyLayout
import ovh.plrapps.mapcompose.vector.spec.style.sky.SkyPaint
import ovh.plrapps.mapcompose.vector.spec.style.symbol.SymbolLayout
import ovh.plrapps.mapcompose.vector.spec.style.symbol.SymbolPaint

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
sealed class Layer {
    abstract val id: String
    abstract val type: String
    abstract val source: String?
    @SerialName("source-layer")
    abstract val sourceLayer: String?
    @Serializable(with = FeatureFilterSerializer::class)
    abstract val filter: FilterHolder?
    abstract val minzoom: Double?
    abstract val maxzoom: Double?
    /**
     * Never null: an omitted `layout` is the same as `"layout": {}`, as it is upstream, where
     * `StyleLayer`'s constructor always builds a fully populated `PossiblyEvaluated` from the
     * style spec (`src/style/style_layer.ts`). A painter reads every property through its
     * `?: StyleSpecDefaults.X` fallback, so an empty object *is* the spec's defaults.
     */
    abstract val layout: LayoutInterface

    /** Never null, for the reason [layout] is not; see there. */
    abstract val paint: PaintInterface
}

@Serializable
@SerialName("line")
data class LineLayer(
    override val id: String,
    override val type: String = "line",
    override val source: String? = null,
    @SerialName("source-layer")
    override val sourceLayer: String? = null,
    @Serializable(with = FeatureFilterSerializer::class)
    override val filter: FilterHolder? = null,
    override val minzoom: Double? = null,
    override val maxzoom: Double? = null,
    override val layout: LineLayout = LineLayout(),
    override val paint: LinePaint = LinePaint(),
) : Layer()

@Serializable
@SerialName("fill")
data class FillLayer(
    override val id: String,
    override val type: String = "fill",
    override val source: String? = null,
    @SerialName("source-layer")
    override val sourceLayer: String? = null,
    @Serializable(with = FeatureFilterSerializer::class)
    override val filter: FilterHolder? = null,
    override val minzoom: Double? = null,
    override val maxzoom: Double? = null,
    override val layout: FillLayout = FillLayout(),
    override val paint: FillPaint = FillPaint(),
) : Layer()

@Serializable
@SerialName("symbol")
data class SymbolLayer(
    override val id: String,
    override val type: String = "symbol",
    override val source: String? = null,
    @SerialName("source-layer")
    override val sourceLayer: String? = null,
    @Serializable(with = FeatureFilterSerializer::class)
    override val filter: FilterHolder? = null,
    override val minzoom: Double? = null,
    override val maxzoom: Double? = null,
    override val layout: SymbolLayout = SymbolLayout(),
    override val paint: SymbolPaint = SymbolPaint(),
) : Layer()

@Serializable
@SerialName("circle")
data class CircleLayer(
    override val id: String,
    override val type: String = "circle",
    override val source: String? = null,
    @SerialName("source-layer")
    override val sourceLayer: String? = null,
    @Serializable(with = FeatureFilterSerializer::class)
    override val filter: FilterHolder? = null,
    override val minzoom: Double? = null,
    override val maxzoom: Double? = null,
    override val layout: CircleLayout = CircleLayout(),
    override val paint: CirclePaint = CirclePaint(),
) : Layer()

@Serializable
@SerialName("background")
data class BackgroundLayer(
    override val id: String,
    override val type: String = "background",
    override val source: String? = null,
    @SerialName("source-layer")
    override val sourceLayer: String? = null,
    @Serializable(with = FeatureFilterSerializer::class)
    override val filter: FilterHolder? = null,
    override val minzoom: Double? = null,
    override val maxzoom: Double? = null,
    override val layout: BackgroundLayout = BackgroundLayout(),
    override val paint: BackgroundPaint = BackgroundPaint(),
) : Layer()

@Serializable
@SerialName("raster")
data class RasterLayer(
    override val id: String,
    override val type: String = "raster",
    override val source: String? = null,
    @SerialName("source-layer")
    override val sourceLayer: String? = null,
    @Serializable(with = FeatureFilterSerializer::class)
    override val filter: FilterHolder? = null,
    override val minzoom: Double? = null,
    override val maxzoom: Double? = null,
    override val layout: RasterLayout = RasterLayout(),
    override val paint: RasterPaint = RasterPaint(),
) : Layer()

@Serializable
@SerialName("hillshade")
data class HillshadeLayer(
    override val id: String,
    override val type: String = "hillshade",
    override val source: String? = null,
    @SerialName("source-layer")
    override val sourceLayer: String? = null,
    @Serializable(with = FeatureFilterSerializer::class)
    override val filter: FilterHolder? = null,
    override val minzoom: Double? = null,
    override val maxzoom: Double? = null,
    override val layout: HillshadeLayout = HillshadeLayout(),
    override val paint: HillshadePaint = HillshadePaint(),
) : Layer()

@Serializable
@SerialName("heatmap")
data class HeatmapLayer(
    override val id: String,
    override val type: String = "heatmap",
    override val source: String? = null,
    @SerialName("source-layer")
    override val sourceLayer: String? = null,
    @Serializable(with = FeatureFilterSerializer::class)
    override val filter: FilterHolder? = null,
    override val minzoom: Double? = null,
    override val maxzoom: Double? = null,
    override val layout: HeatmapLayout = HeatmapLayout(),
    override val paint: HeatmapPaint = HeatmapPaint(),
) : Layer()

@Serializable
@SerialName("fill-extrusion")
data class FillExtrusionLayer(
    override val id: String,
    override val type: String = "fill-extrusion",
    override val source: String? = null,
    @SerialName("source-layer")
    override val sourceLayer: String? = null,
    @Serializable(with = FeatureFilterSerializer::class)
    override val filter: FilterHolder? = null,
    override val layout: FillExtrusionLayout = FillExtrusionLayout(),
    override val paint: FillExtrusionPaint = FillExtrusionPaint(),
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
    @Serializable(with = FeatureFilterSerializer::class)
    override val filter: FilterHolder? = null,
    override val minzoom: Double? = null,
    override val maxzoom: Double? = null,
    override val layout: SkyLayout = SkyLayout(),
    override val paint: SkyPaint = SkyPaint(),
) : Layer()

