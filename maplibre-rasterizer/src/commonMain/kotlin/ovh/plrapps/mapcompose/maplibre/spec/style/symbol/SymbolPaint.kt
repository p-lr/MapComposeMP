package ovh.plrapps.mapcompose.maplibre.spec.style.symbol

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.maplibre.spec.style.PaintInterface
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValueColorSerializer
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SymbolPaint(
    @SerialName("icon-color")
    @Serializable(with = ExpressionOrValueColorSerializer::class)
    val iconColor: ExpressionOrValue<Color>? = null,

    @SerialName("icon-halo-blur")
    val iconHaloBlur: ExpressionOrValue<Double>? = null,
    @SerialName("icon-halo-color")
    @Serializable(with = ExpressionOrValueColorSerializer::class)
    val iconHaloColor: ExpressionOrValue<Color>? = null,
    @SerialName("icon-halo-width")
    val iconHaloWidth: ExpressionOrValue<Double>? = null,
    @SerialName("icon-opacity")
    val iconOpacity: ExpressionOrValue<Double>? = null,
    @SerialName("icon-translate")
    val iconTranslate: ExpressionOrValue<List<Double>>? = null,
    @SerialName("icon-translate-anchor")
    val iconTranslateAnchor: ExpressionOrValue<String>? = null,
    @SerialName("text-color")
    @Serializable(with = ExpressionOrValueColorSerializer::class)
    val textColor: ExpressionOrValue<Color>? = null,
    @SerialName("text-halo-blur")
    val textHaloBlur: ExpressionOrValue<Double>? = null,
    @SerialName("text-halo-color")
    @Serializable(with = ExpressionOrValueColorSerializer::class)
    val textHaloColor: ExpressionOrValue<Color>? = null,
    @SerialName("text-halo-width")
    val textHaloWidth: ExpressionOrValue<Double>? = null,
    @SerialName("text-opacity")
    val textOpacity: ExpressionOrValue<Double>? = null,
    @SerialName("text-translate")
    val textTranslate: ExpressionOrValue<List<Double>>? = null,
    @SerialName("text-translate-anchor")
    val textTranslateAnchor: ExpressionOrValue<String>? = null
) : PaintInterface