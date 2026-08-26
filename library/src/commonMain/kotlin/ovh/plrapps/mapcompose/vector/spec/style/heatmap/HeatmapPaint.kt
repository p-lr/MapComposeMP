package ovh.plrapps.mapcompose.vector.spec.style.heatmap

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ovh.plrapps.mapcompose.vector.spec.style.PaintInterface
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValueColorSerializer

@Serializable
data class HeatmapPaint(
    @SerialName("heatmap-weight")
    val heatmapWeight: ExpressionOrValue<Double>? = null,

    @SerialName("heatmap-intensity")
    val heatmapIntensity: ExpressionOrValue<Double>? = null,

    /**
     * The density-to-colour ramp. Unlike every other colour property this one is not read at a
     * feature or a zoom but at a density, so it goes through
     * [ovh.plrapps.mapcompose.vector.spec.style.props.processAsHeatmapColor].
     */
    @SerialName("heatmap-color")
    @Serializable(with = ExpressionOrValueColorSerializer::class)
    val heatmapColor: ExpressionOrValue<Color>? = null,

    @SerialName("heatmap-radius")
    val heatmapRadius: ExpressionOrValue<Double>? = null,

    @SerialName("heatmap-opacity")
    val heatmapOpacity: ExpressionOrValue<Double>? = null
) : PaintInterface
