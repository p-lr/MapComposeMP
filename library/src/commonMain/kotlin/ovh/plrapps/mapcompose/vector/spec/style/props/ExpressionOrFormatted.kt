package ovh.plrapps.mapcompose.vector.spec.style.props

import kotlinx.serialization.KSerializer
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.Formatted
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.ResolvedImage
import ovh.plrapps.mapcompose.vector.spec.style.serializers.ExpressionOrValueSerializer
import ovh.plrapps.mapcompose.vector.spec.style.serializers.FormattedSerializer
import ovh.plrapps.mapcompose.vector.spec.style.serializers.ResolvedImageSerializer

/**
 * Property serializers for the two spec types that are neither a scalar nor a colour.
 *
 * They exist for the same reason [ExpressionOrValueColorSerializer] does: the *value* serializer is
 * what tells [ExpressionOrValueSerializer] which expression type the property expects, and getting
 * that wrong silently discards the property. `text-field` typed as a plain string made every
 * `["format", ...]` in a style compile to [ExpressionOrValue.Invalid] and the label vanish.
 */
val formattedSerializer: KSerializer<Formatted> = FormattedSerializer

val resolvedImageSerializer: KSerializer<ResolvedImage> = ResolvedImageSerializer

object ExpressionOrValueFormattedSerializer :
    KSerializer<ExpressionOrValue<Formatted>> by ExpressionOrValueSerializer(formattedSerializer)

object ExpressionOrValueResolvedImageSerializer :
    KSerializer<ExpressionOrValue<ResolvedImage>> by ExpressionOrValueSerializer(resolvedImageSerializer)
