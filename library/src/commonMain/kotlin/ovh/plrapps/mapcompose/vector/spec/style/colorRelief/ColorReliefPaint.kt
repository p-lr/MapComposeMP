package ovh.plrapps.mapcompose.vector.spec.style.colorRelief

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ovh.plrapps.mapcompose.vector.spec.style.PaintInterface
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValueColorSerializer

/**
 * `color-relief`'s paint properties.
 *
 * [resampling] really is spelled `resampling` and not `color-relief-resampling`: it is the one
 * property in `paint_color-relief` that carries no layer-type prefix in `v8.json`, and upstream
 * reads it as `layer.paint.get('resampling')` in `src/webgl/draw/draw_color_relief.ts`.
 */
@Serializable
data class ColorReliefPaint(
    @SerialName("color-relief-color")
    @Serializable(with = ExpressionOrValueColorSerializer::class)
    val colorReliefColor: ExpressionOrValue<Color>? = null,

    @SerialName("color-relief-opacity")
    val colorReliefOpacity: ExpressionOrValue<Double>? = null,

    @SerialName("resampling")
    val resampling: ExpressionOrValue<String>? = null,
) : PaintInterface
