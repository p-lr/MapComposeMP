package ovh.plrapps.mapcompose.vector.spec.style.props

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.KSerializer
import ovh.plrapps.mapcompose.vector.spec.style.serializers.ColorSerializer
import ovh.plrapps.mapcompose.vector.spec.style.serializers.ExpressionOrValueSerializer

val colorSerializer: KSerializer<Color> = ColorSerializer


object ExpressionOrValueColorSerializer :
    KSerializer<ExpressionOrValue<Color>> by ExpressionOrValueSerializer(colorSerializer)
