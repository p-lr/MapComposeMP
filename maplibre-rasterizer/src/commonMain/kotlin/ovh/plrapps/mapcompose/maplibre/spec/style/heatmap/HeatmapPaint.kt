package ovh.plrapps.mapcompose.maplibre.spec.style.heatmap

import ovh.plrapps.mapcompose.maplibre.spec.style.PaintInterface
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HeatmapPaint(
    @SerialName("heatmap-weight")
    val heatmapWeight: ExpressionOrValue<Double>? = null,

    @SerialName("heatmap-intensity")
    val heatmapIntensity: ExpressionOrValue<Double>? = null,

    @SerialName("heatmap-color")
    val heatmapColor: ExpressionOrValue<String>? = null,

    @SerialName("heatmap-radius")
    val heatmapRadius: ExpressionOrValue<Double>? = null,

    @SerialName("heatmap-opacity")
    val heatmapOpacity: ExpressionOrValue<Double>? = null
) : PaintInterface