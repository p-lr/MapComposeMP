package ovh.plrapps.mapcompose.vector.renderer.utils

/**
 * A `padding`-typed style property's four sides, in `[top, right, bottom, left]` order.
 *
 * The style spec types `icon-padding` `padding` with a default of `[2]`, and upstream's
 * `getIconPadding` (`style/style_layer/symbol_style_layer.ts`) hands collision four values:
 *
 * ```
 * const result = layout.get('icon-padding').evaluate(feature, {}, canonical);
 * return [values[0] * pixelRatio, values[1] * pixelRatio, values[2] * pixelRatio, values[3] * pixelRatio];
 * ```
 *
 * It was modelled here as a single `Double`, so an asymmetric `[0, 20, 0, 0]` failed the constant
 * decode, became a diagnostic, and the property fell back to the scalar default of 2.
 */
data class PaddingSides(
    val top: Float,
    val right: Float,
    val bottom: Float,
    val left: Float,
) {
    val width: Float get() = left + right
    val height: Float get() = top + bottom

    /** How far the padded box's centre moves from the unpadded one. */
    val centerShiftX: Float get() = (right - left) / 2f
    val centerShiftY: Float get() = (bottom - top) / 2f

    companion object {
        /** The same padding on every side, which is what a `number`-typed property gives. */
        fun uniform(padding: Float): PaddingSides = PaddingSides(padding, padding, padding, padding)
    }
}

/**
 * Expands a `padding` value into its four sides, by CSS's 1/2/3/4-value rules.
 *
 * Port of `Padding.parse` (`maplibre-style-spec/src/expression/types/padding.ts`): one value is
 * every side, two are `[vertical, horizontal]`, three are `[top, horizontal, bottom]`. A bare scalar
 * has already become a one-element array by then -- the property is carried as a `NumberArray`,
 * whose `parse` does that -- which is the backwards compatibility every style written against the
 * old `number` typing relies on.
 *
 * An empty or absent value falls back to [default] on every side; a value longer than four is
 * truncated rather than refused, the spec's own range being 1..4.
 */
fun paddingSides(values: List<Double>?, default: Double): PaddingSides {
    val v = values.orEmpty()
    return when (v.size) {
        0 -> PaddingSides.uniform(default.toFloat())
        1 -> PaddingSides.uniform(v[0].toFloat())
        2 -> PaddingSides(v[0].toFloat(), v[1].toFloat(), v[0].toFloat(), v[1].toFloat())
        3 -> PaddingSides(v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), v[1].toFloat())
        else -> PaddingSides(v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), v[3].toFloat())
    }
}

/** [paddingSides] with every side scaled, which is upstream's `* pixelRatio`. */
fun paddingSides(values: List<Double>?, default: Double, scale: Float): PaddingSides =
    paddingSides(values, default).let {
        PaddingSides(it.top * scale, it.right * scale, it.bottom * scale, it.left * scale)
    }
