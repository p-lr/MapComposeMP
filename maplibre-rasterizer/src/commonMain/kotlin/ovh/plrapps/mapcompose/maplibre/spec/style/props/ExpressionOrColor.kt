package ovh.plrapps.mapcompose.maplibre.spec.style.props

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.maplibre.spec.style.serializers.ColorSerializer
import ovh.plrapps.mapcompose.maplibre.spec.style.serializers.ExpressionOrValueSerializer
import kotlinx.serialization.KSerializer

val colorSerializer: KSerializer<Color> = ColorSerializer


object ExpressionOrValueColorSerializer :
    KSerializer<ExpressionOrValue<Color>> by ExpressionOrValueSerializer(colorSerializer)
