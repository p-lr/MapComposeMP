package ovh.plrapps.mapcompose.vector.symbol

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationKind
import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.Interpolate
import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.InterpolationType
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsDouble

/**
 * `text-size` / `icon-size` split across the two passes, ported from
 * `maplibre-gl-js/src/symbol/symbol_size.ts`.
 *
 * A symbol is laid out once, at its bucket's zoom, but drawn at whatever zoom the map is at when
 * placement runs. The size expression therefore cannot simply be evaluated at layout time: what the
 * layout pass bakes is [SizeData] plus, for a size that varies per feature, that feature's size at
 * the two zooms bracketing the bucket. [evaluateSizeForZoom] and [evaluateSizeForFeature] then give
 * the size at the drawn zoom, and the ratio to the size the label was rasterized at is what scales
 * it -- upstream's `textScale` / `iconScale`.
 *
 * Two upstream constants are deliberately absent. `SIZE_PACK_FACTOR` and `MAX_PACKED_SIZE` exist
 * because upstream packs a feature's two sizes into a `Uint16` vertex attribute; sizes are plain
 * `Double`s here, so there is nothing to pack and nothing to overflow. `MAX_GLYPH_ICON_SIZE` is the
 * cap that follows from that packing and is likewise not needed.
 */
internal sealed class SizeData {

    /** The size does not vary: one value for the whole bucket. */
    data class Constant(val layoutSize: Double) : SizeData()

    /** The size varies per feature but not with zoom. */
    data object Source : SizeData()

    /** The size varies with zoom but not per feature. */
    data class Camera(
        val zoomStops: List<Double>,
        val sizes: List<Double>,
        val layoutSize: Double,
        val interpolationType: InterpolationType?,
    ) : SizeData()

    /** The size varies with zoom *and* per feature; only the bracketing stops are kept. */
    data class Composite(
        val minZoom: Double,
        val maxZoom: Double,
        val interpolationType: InterpolationType?,
    ) : SizeData()
}

/** A size expression evaluated at one zoom: upstream's `{uSizeT, uSize}` uniform pair. */
internal data class EvaluatedZoomSize(val sizeT: Double, val size: Double)

/** One feature's size at the two zooms bracketing its bucket -- upstream's `lowerSize`/`upperSize`. */
internal data class FeatureSizes(val lowerSize: Double, val upperSize: Double)

/**
 * The bucket-level size data for one size property, ported from `getSizeData`.
 *
 * [tileZoom] is the bucket's own integer zoom. Note that a constant's and a camera function's
 * [SizeData.Constant.layoutSize] is sampled at `tileZoom + 1`, not at `tileZoom`: that is the
 * largest the symbol will be drawn at while this bucket is on screen, so the label is rasterized
 * there and every later [evaluateSizeForZoom] scales it *down*. [evaluateCameraSize] caps at it for
 * the same reason upstream gives -- a symbol drawn larger than its collision box was built for
 * would overlap its neighbours.
 */
internal fun getSizeData(
    tileZoom: Double,
    property: ExpressionOrValue<Double>?,
    default: Double,
): SizeData {
    val expression = (property as? ExpressionOrValue.Expression)?.expression
        ?: return SizeData.Constant(property.processAsDouble(zoom = tileZoom + 1) ?: default)

    return when (expression.kind) {
        EvaluationKind.CONSTANT ->
            SizeData.Constant(property.processAsDouble(zoom = tileZoom + 1) ?: default)

        EvaluationKind.SOURCE -> SizeData.Source

        EvaluationKind.COMPOSITE -> {
            val (minZoom, maxZoom) = getCoveringZoomStops(expression.zoomStops.orEmpty(), tileZoom)
            SizeData.Composite(minZoom, maxZoom, expression.interpolationType)
        }

        EvaluationKind.CAMERA -> {
            val zoomStops = expression.zoomStops.orEmpty()
            SizeData.Camera(
                zoomStops = zoomStops,
                sizes = evaluateSizesAtZoomStops(property, zoomStops, default),
                layoutSize = property.processAsDouble(zoom = tileZoom + 1) ?: default,
                interpolationType = expression.interpolationType,
            )
        }
    }
}

/**
 * The pair of zoom stops covering `[tileZoom, tileZoom + 1]`, ported from `getCoveringZoomStops`.
 *
 * A composite size bakes each feature's size at exactly these two zooms, which is all the placement
 * pass has to blend between.
 */
internal fun getCoveringZoomStops(zoomStops: List<Double>, tileZoom: Double): Pair<Double, Double> {
    if (zoomStops.isEmpty()) return tileZoom to tileZoom + 1
    var lower = 0
    while (lower < zoomStops.size && zoomStops[lower] <= tileZoom) lower++
    lower = maxOf(0, lower - 1)
    var upper = lower
    while (upper < zoomStops.size && zoomStops[upper] < tileZoom + 1) upper++
    upper = minOf(zoomStops.size - 1, upper)
    return zoomStops[lower] to zoomStops[upper]
}

/**
 * A camera size expression sampled at each of its own zoom stops, ported from
 * `evaluateSizesAtZoomStops`. A `step`'s first stop is `-Infinity`, so its base value is sampled
 * just below the second stop instead.
 */
private fun evaluateSizesAtZoomStops(
    property: ExpressionOrValue<Double>?,
    zoomStops: List<Double>,
    default: Double,
): List<Double> = zoomStops.map { stop ->
    val at = if (stop == Double.NEGATIVE_INFINITY) zoomStops.getOrElse(1) { 0.0 } - 1.0 else stop
    property.processAsDouble(zoom = at) ?: default
}

/** One feature's sizes at the bucket's bracketing zooms; only read for a source or composite size. */
internal fun getFeatureSizes(
    sizeData: SizeData,
    property: ExpressionOrValue<Double>?,
    feature: EvalFeature?,
    tileZoom: Double,
    default: Double,
): FeatureSizes = when (sizeData) {
    is SizeData.Source -> {
        val size = property.processAsDouble(feature, tileZoom) ?: default
        FeatureSizes(size, size)
    }

    is SizeData.Composite -> FeatureSizes(
        lowerSize = property.processAsDouble(feature, sizeData.minZoom) ?: default,
        upperSize = property.processAsDouble(feature, sizeData.maxZoom) ?: default,
    )

    else -> FeatureSizes(0.0, 0.0)
}

/**
 * A bucket's size at the zoom being drawn, ported from `evaluateSizeForZoom`.
 *
 * On a retained tile that zoom differs from the one the bucket was built for, which is the whole
 * reason this indirection exists.
 */
internal fun evaluateSizeForZoom(sizeData: SizeData, zoom: Double): EvaluatedZoomSize = when (sizeData) {
    is SizeData.Constant -> EvaluatedZoomSize(sizeT = 0.0, size = sizeData.layoutSize)
    is SizeData.Camera -> EvaluatedZoomSize(sizeT = 0.0, size = evaluateCameraSize(sizeData, zoom))
    is SizeData.Composite ->
        EvaluatedZoomSize(sizeT = evaluateCompositeInterpolationFactor(sizeData, zoom), size = 0.0)

    is SizeData.Source -> EvaluatedZoomSize(sizeT = 0.0, size = 0.0)
}

/** One feature's size at the drawn zoom, ported from `evaluateSizeForFeature`. */
internal fun evaluateSizeForFeature(
    sizeData: SizeData,
    zoomSize: EvaluatedZoomSize,
    featureSizes: FeatureSizes,
): Double = when (sizeData) {
    is SizeData.Source -> featureSizes.lowerSize
    is SizeData.Composite ->
        featureSizes.lowerSize + (featureSizes.upperSize - featureSizes.lowerSize) * zoomSize.sizeT

    else -> zoomSize.size
}

/**
 * A camera size at the drawn zoom, interpolating between the stops around it, ported from
 * `evaluateCameraSize`. Capped at `layoutSize` -- see [getSizeData].
 */
private fun evaluateCameraSize(sizeData: SizeData.Camera, zoom: Double): Double {
    val zoomStops = sizeData.zoomStops
    if (zoomStops.isEmpty()) return sizeData.layoutSize

    var lower = zoomStops.size - 1
    while (lower > 0 && zoomStops[lower] > zoom) lower--
    val upper = minOf(lower + 1, zoomStops.size - 1)

    val t = sizeData.interpolationType?.let {
        Interpolate.interpolationFactor(it, zoom, zoomStops[lower], zoomStops[upper]).coerceIn(0.0, 1.0)
    } ?: 0.0

    val lowerSize = sizeData.sizes.getOrElse(lower) { sizeData.layoutSize }
    val upperSize = sizeData.sizes.getOrElse(upper) { sizeData.layoutSize }
    return minOf(lowerSize + (upperSize - lowerSize) * t, sizeData.layoutSize)
}

/**
 * How far the drawn zoom sits between a composite size's two stops, clamped into `0..1`, ported
 * from `evaluateCompositeInterpolationFactor`.
 */
private fun evaluateCompositeInterpolationFactor(sizeData: SizeData.Composite, zoom: Double): Double =
    sizeData.interpolationType?.let {
        Interpolate.interpolationFactor(it, zoom, sizeData.minZoom, sizeData.maxZoom).coerceIn(0.0, 1.0)
    } ?: 0.0

/**
 * A bucket's two size properties, built once per style layer per tile.
 *
 * [tileZoom] is what [getSizeData] brackets against, and is the bucket's integer zoom **minus one**.
 * Upstream's tile for a view at zoom `f` is `floor(f)`, so its bucket covers `[z, z + 1)` and it
 * rasterizes at `z + 1`; MapCompose's `VisibleTilesResolver` rounds the level *up* instead, so the
 * tile on screen at `f` is level `ceil(f)` and the range a bucket has to cover is `(z - 1, z]`.
 * Passing `z - 1` here brackets that range with upstream's own code, and keeps upstream's guarantee
 * that the symbol is rasterized at the largest size it will be drawn at -- so every later scale is a
 * minification and a label is never magnified.
 */
internal class SymbolSizes(
    val tileZoom: Double,
    val textSizeData: SizeData,
    val iconSizeData: SizeData,
)
