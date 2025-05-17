package ovh.plrapps.mapcompose.maplibre.spec.style.symbol

import ovh.plrapps.mapcompose.maplibre.spec.style.LayoutInterface
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SymbolLayout(
    override val visibility: String? = "visible",

    @SerialName("icon-allow-overlap")
    val iconAllowOverlap: ExpressionOrValue<Boolean>? = null,

    @SerialName("icon-ignore-placement")
    val iconIgnorePlacement: ExpressionOrValue<Boolean>? = null,

    @SerialName("icon-optional")
    val iconOptional: ExpressionOrValue<Boolean>? = null,

    @SerialName("icon-rotation-alignment")
    val iconRotationAlignment: ExpressionOrValue<String>? = null,

    @SerialName("icon-size")
    val iconSize: ExpressionOrValue<Double>? = null,

    @SerialName("icon-text-fit")
    val iconTextFit: ExpressionOrValue<String>? = null,

    @SerialName("icon-text-fit-padding")
    val iconTextFitPadding: ExpressionOrValue<List<Double>>? = null,

    @SerialName("icon-image")
    val iconImage: ExpressionOrValue<String>? = null,

    @SerialName("icon-rotate")
    val iconRotate: ExpressionOrValue<Double>? = null,

    @SerialName("icon-padding")
    val iconPadding: ExpressionOrValue<Double>? = null,

    @SerialName("icon-keep-upright")
    val iconKeepUpright: ExpressionOrValue<Boolean>? = null,

    @SerialName("icon-offset")
    val iconOffset: ExpressionOrValue<List<Double>>? = null,

    @SerialName("icon-anchor")
    val iconAnchor: ExpressionOrValue<String>? = null,

    @SerialName("icon-pitch-alignment")
    val iconPitchAlignment: ExpressionOrValue<String>? = null,

    @SerialName("text-pitch-alignment")
    val textPitchAlignment: ExpressionOrValue<String>? = null,

    @SerialName("text-rotation-alignment")
    val textRotationAlignment: ExpressionOrValue<String>? = null,

    @SerialName("text-field")
    val textField: ExpressionOrValue<String>? = null,

    @SerialName("text-font")
    val textFont: ExpressionOrValue<List<String>>? = null,

    @SerialName("text-size")
    val textSize: ExpressionOrValue<Double>? = null,

    @SerialName("text-max-width")
    val textMaxWidth: ExpressionOrValue<Double>? = null,

    @SerialName("text-line-height")
    val textLineHeight: ExpressionOrValue<Double>? = null,

    @SerialName("text-letter-spacing")
    val textLetterSpacing: ExpressionOrValue<Double>? = null,

    @SerialName("text-justify")
    val textJustify: ExpressionOrValue<String>? = null,

    @SerialName("text-radial-offset")
    val textRadialOffset: ExpressionOrValue<Double>? = null,

    @SerialName("text-variable-anchor")
    val textVariableAnchor: ExpressionOrValue<List<String>>? = null,

    @SerialName("text-anchor")
    val textAnchor: ExpressionOrValue<String>? = null,

    @SerialName("text-max-angle")
    val textMaxAngle: ExpressionOrValue<Double>? = null,

    @SerialName("text-writing-mode")
    val textWritingMode: ExpressionOrValue<List<String>>? = null,

    @SerialName("text-rotate")
    val textRotate: ExpressionOrValue<Double>? = null,

    @SerialName("text-padding")
    val textPadding: ExpressionOrValue<Double>? = null,

    @SerialName("text-keep-upright")
    val textKeepUpright: ExpressionOrValue<Boolean>? = null,

    @SerialName("text-transform")
    val textTransform: ExpressionOrValue<String>? = null,

    @SerialName("text-offset")
    val textOffset: ExpressionOrValue<List<Double>>? = null,

    @SerialName("text-allow-overlap")
    val textAllowOverlap: ExpressionOrValue<Boolean>? = null,

    @SerialName("text-ignore-placement")
    val textIgnorePlacement: ExpressionOrValue<Boolean>? = null,

    @SerialName("text-optional")
    val textOptional: ExpressionOrValue<Boolean>? = null,

    @SerialName("symbol-placement")
    val symbolPlacement: ExpressionOrValue<String>? = null,

    @SerialName("symbol-spacing")
    val symbolSpacing: ExpressionOrValue<Double>? = null,

    @SerialName("symbol-avoid-edges")
    val symbolAvoidEdges: ExpressionOrValue<Boolean>? = null,

    @SerialName("symbol-sort-key")
    val symbolSortKey: ExpressionOrValue<Double>? = null,

    @SerialName("symbol-z-order")
    val symbolZOrder: ExpressionOrValue<String>? = null,

    @SerialName("icon-overlap")
    val iconOverlap: ExpressionOrValue<String>? = null,

    @SerialName("text-overlap")
    val textOverlap: ExpressionOrValue<String>? = null
) : LayoutInterface