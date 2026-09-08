package ovh.plrapps.mapcompose.vector.spec.style.expression.types

import ovh.plrapps.mapcompose.vector.spec.style.expression.colorspaces.interpolateNumber

/**
 * An array of numbers, the runtime value of a `numberArray`-typed style property.
 *
 * Ported from `maplibre-style-spec/src/expression/types/number_array.ts`.
 *
 * It is a class rather than a bare `List<Double>` for the same reason upstream's is: `typeOf` has
 * to tell a `numberArray` from an `array<number>`, and only the former accepts a bare scalar.
 */
data class NumberArray(val values: List<Double>) {

    override fun toString(): String = values.joinToString(prefix = "[", postfix = "]")

    companion object {
        /**
         * Coerces [input] into a [NumberArray], or returns `null` if it cannot be one.
         *
         * A bare number is treated as a one-element array, which is upstream's explicit
         * backwards-compatibility rule for `hillshade-illumination-direction`: every style written
         * before multidirectional hillshade existed spells it as a scalar.
         */
        fun parse(input: Any?): NumberArray? = when (input) {
            is NumberArray -> input
            is Number -> NumberArray(listOf(input.toDouble()))
            is List<*> -> {
                val values = mutableListOf<Double>()
                var valid = true
                for (value in input) {
                    if (value is Number) values.add(value.toDouble()) else { valid = false; break }
                }
                if (valid) NumberArray(values) else null
            }

            else -> null
        }

        fun interpolate(from: NumberArray, to: NumberArray, t: Double): NumberArray =
            NumberArray(from.values.mapIndexed { i, v -> interpolateNumber(v, to.values[i], t) })
    }
}
