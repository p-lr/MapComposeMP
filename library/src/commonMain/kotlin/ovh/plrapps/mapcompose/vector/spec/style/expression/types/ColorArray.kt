package ovh.plrapps.mapcompose.vector.spec.style.expression.types

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.vector.spec.style.expression.colorspaces.InterpolationColorSpace
import ovh.plrapps.mapcompose.vector.spec.style.expression.colorspaces.interpolateColor
import ovh.plrapps.mapcompose.vector.spec.style.utils.ColorParser

/**
 * An array of colours, the runtime value of a `colorArray`-typed style property.
 *
 * Ported from `maplibre-style-spec/src/expression/types/color_array.ts`. See [NumberArray] for why
 * it is a class rather than a `List<Color>`.
 */
data class ColorArray(val values: List<Color>) {

    override fun toString(): String = values.joinToString(prefix = "[", postfix = "]")

    companion object {
        /**
         * Coerces [input] into a [ColorArray], or returns `null` if it cannot be one.
         *
         * A bare colour -- a parseable string, or an already-parsed [Color] -- is treated as a
         * one-element array, upstream's backwards-compatibility rule for
         * `hillshade-shadow-color`.
         */
        fun parse(input: Any?): ColorArray? = when (input) {
            is ColorArray -> input
            is Color -> ColorArray(listOf(input))
            is String -> ColorParser.parseColorStringOrNull(input)?.let { ColorArray(listOf(it)) }
            is List<*> -> {
                val values = mutableListOf<Color>()
                var valid = true
                for (value in input) {
                    val color = when (value) {
                        is Color -> value
                        is String -> ColorParser.parseColorStringOrNull(value)
                        else -> null
                    }
                    if (color != null) values.add(color) else { valid = false; break }
                }
                if (valid) ColorArray(values) else null
            }

            else -> null
        }

        /** Throws when the two have different lengths, as upstream does -- there is no sensible
         *  colour to interpolate a missing light source towards. */
        fun interpolate(
            from: ColorArray,
            to: ColorArray,
            t: Double,
            space: InterpolationColorSpace = InterpolationColorSpace.RGB,
        ): ColorArray {
            if (from.values.size != to.values.size) {
                throw IllegalArgumentException(
                    "colorArray: Arrays have mismatched length (${from.values.size} vs. " +
                            "${to.values.size}), cannot interpolate."
                )
            }
            return ColorArray(from.values.mapIndexed { i, v -> interpolateColor(v, to.values[i], t, space) })
        }
    }
}
