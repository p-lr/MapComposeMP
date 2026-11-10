package ovh.plrapps.mapcompose.vector.spec.style.hillshade

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ovh.plrapps.mapcompose.vector.spec.style.PaintInterface
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.ColorArray
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.NumberArray
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValueColorArraySerializer
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValueColorSerializer
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValueNumberArraySerializer

/**
 * `hillshade`'s paint properties.
 *
 * Four of them are arrays, not scalars: a style may declare several illumination sources, which
 * `hillshade-method: multidirectional` averages over and every other method reads the first of.
 * A bare number or colour is a one-element array, so nothing a pre-multidirectional style wrote
 * changes meaning -- see `NumberArray.parse`.
 *
 * [resampling] really is spelled `resampling` and not `hillshade-resampling`, exactly as it is on
 * `color-relief`.
 */
@Serializable
data class HillshadePaint(
    @SerialName("hillshade-accent-color")
    @Serializable(with = ExpressionOrValueColorSerializer::class)
    val hillshadeAccentColor: ExpressionOrValue<Color>? = null,

    @SerialName("hillshade-exaggeration")
    val hillshadeExaggeration: ExpressionOrValue<Double>? = null,

    @SerialName("hillshade-highlight-color")
    @Serializable(with = ExpressionOrValueColorArraySerializer::class)
    val hillshadeHighlightColor: ExpressionOrValue<ColorArray>? = null,

    @SerialName("hillshade-illumination-altitude")
    @Serializable(with = ExpressionOrValueNumberArraySerializer::class)
    val hillshadeIlluminationAltitude: ExpressionOrValue<NumberArray>? = null,

    @SerialName("hillshade-illumination-anchor")
    val hillshadeIlluminationAnchor: ExpressionOrValue<String>? = null,

    @SerialName("hillshade-illumination-direction")
    @Serializable(with = ExpressionOrValueNumberArraySerializer::class)
    val hillshadeIlluminationDirection: ExpressionOrValue<NumberArray>? = null,

    @SerialName("hillshade-method")
    val hillshadeMethod: ExpressionOrValue<String>? = null,

    @SerialName("hillshade-shadow-color")
    @Serializable(with = ExpressionOrValueColorArraySerializer::class)
    val hillshadeShadowColor: ExpressionOrValue<ColorArray>? = null,

    @SerialName("resampling")
    val resampling: ExpressionOrValue<String>? = null,
) : PaintInterface
