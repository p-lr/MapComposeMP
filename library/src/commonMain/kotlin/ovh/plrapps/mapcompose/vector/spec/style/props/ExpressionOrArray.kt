package ovh.plrapps.mapcompose.vector.spec.style.props

import kotlinx.serialization.KSerializer
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.ColorArray
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.NumberArray
import ovh.plrapps.mapcompose.vector.spec.style.serializers.ColorArraySerializer
import ovh.plrapps.mapcompose.vector.spec.style.serializers.ExpressionOrValueSerializer
import ovh.plrapps.mapcompose.vector.spec.style.serializers.NumberArraySerializer

/**
 * Property serializers for the two array spec types, `numberArray` and `colorArray`.
 *
 * Only `hillshade`'s illumination properties use them, and they exist for the reason
 * [ExpressionOrValueColorSerializer] does: the *value* serializer is what tells
 * [ExpressionOrValueSerializer] which expression type the property expects. Typed as a scalar --
 * which is what these were before multidirectional hillshade was ported -- an array-valued
 * `hillshade-illumination-direction` threw out of the value decoder, and since
 * `getMapLibreConfiguration` turns a decode failure into `Result.failure`, one such property took
 * the whole style down with it.
 */
val numberArraySerializer: KSerializer<NumberArray> = NumberArraySerializer

val colorArraySerializer: KSerializer<ColorArray> = ColorArraySerializer

object ExpressionOrValueNumberArraySerializer :
    KSerializer<ExpressionOrValue<NumberArray>> by ExpressionOrValueSerializer(numberArraySerializer)

object ExpressionOrValueColorArraySerializer :
    KSerializer<ExpressionOrValue<ColorArray>> by ExpressionOrValueSerializer(colorArraySerializer)
