package ovh.plrapps.mapcompose.vector.spec.style.expression

/**
 * The subset of MapLibre's `StylePropertySpecification` this engine needs.
 *
 * Upstream reads a generated JSON metadata file describing every style property. MapCompose models
 * style properties as Kotlin data classes instead, so the relevant facts are supplied by the
 * property serializer at the point of use:
 *
 * - [expectedType] drives the parser's automatic assert/coerce annotation;
 * - [defaultValue] is what [StyleExpression.evaluate] falls back to on a runtime error — the
 *   painters already apply MapLibre's spec defaults with `?:`, so `null` is the usual choice here;
 * - [enumValues] rejects out-of-range results for enum-typed properties;
 * - [supportsPropertyExpression] / [supportsZoomExpression] / [supportsInterpolation] gate the
 *   data-driven, zoom-driven and interpolatable behaviours.
 */
data class StylePropertySpec(
    val expectedType: ExprType?,
    val defaultValue: Any? = null,
    val enumValues: Set<String>? = null,
    val supportsPropertyExpression: Boolean = true,
    val supportsZoomExpression: Boolean = true,
    val supportsInterpolation: Boolean = true,
    /**
     * Whether `"{token}"` strings in a legacy function are rewritten into `concat`/`get`.
     *
     * MapLibre sets this for `text-field` and `icon-image`. MapCompose defaults it to **false**
     * because `SymbolLayerPainter` performs token substitution itself, and converting here as well
     * would double-apply it.
     */
    val tokens: Boolean = false,
) {
    /** True for `string`-typed properties, which coerce rather than assert at the top level. */
    val isStringProperty: Boolean get() = expectedType == StringType

    companion object {
        /** The spec a layer `filter` is compiled against. */
        val FILTER = StylePropertySpec(
            expectedType = BooleanType,
            defaultValue = false,
            supportsInterpolation = false,
        )
    }
}
