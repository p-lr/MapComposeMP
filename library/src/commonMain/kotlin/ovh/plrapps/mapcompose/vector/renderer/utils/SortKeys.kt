package ovh.plrapps.mapcompose.vector.renderer.utils

import ovh.plrapps.mapcompose.vector.spec.style.CircleLayer
import ovh.plrapps.mapcompose.vector.spec.style.FillLayer
import ovh.plrapps.mapcompose.vector.spec.style.Layer
import ovh.plrapps.mapcompose.vector.spec.style.LineLayer
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsDouble

/**
 * The `*-sort-key` of a layer, or `null` when it declares none.
 *
 * MapLibre sorts a bucket's features by sort key ascending and draws them in that order, so a
 * higher key draws on top (`sortFeaturesByKey`, called from each bucket's `populate`). Absent a sort
 * key, features keep their order in the tile -- which is what the renderer does by skipping the
 * sort entirely.
 *
 * `circle-sort-key` is a layout property per the spec; it is also declared on [CircleLayer]'s paint
 * here, because styles in the wild put it in either place and the parser is deliberately lenient.
 * Layout wins when both are present.
 */
fun sortKeyOf(styleLayer: Layer): ExpressionOrValue<Double>? = when (styleLayer) {
    is FillLayer -> styleLayer.layout.fillSortKey
    is LineLayer -> styleLayer.layout.lineSortKey
    is CircleLayer -> styleLayer.layout.circleSortKey ?: styleLayer.paint.circleSortKey
    else -> null
}

/**
 * Evaluates [sortKey] for one feature. Features whose key does not evaluate to a number sort as if
 * it were `0`, matching upstream's `sortKey ?? 0` in the bucket populate step.
 */
fun evaluateSortKey(
    sortKey: ExpressionOrValue<Double>,
    featureProperties: EvalFeature?,
    zoom: Double,
): Double = sortKey.processAsDouble(featureProperties, zoom) ?: 0.0
