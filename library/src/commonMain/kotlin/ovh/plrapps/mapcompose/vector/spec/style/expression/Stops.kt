package ovh.plrapps.mapcompose.vector.spec.style.expression

import kotlin.math.floor

typealias Stops = List<Pair<Double, Expression>>

/**
 * Returns the index of the last stop <= input, or 0 if it doesn't exist.
 *
 * Ported from `maplibre-style-spec/src/expression/stops.ts`.
 */
fun findStopLessThanOrEqualTo(stops: List<Double>, input: Double, key: String): Int {
    val lastIndex = stops.size - 1
    var lowerIndex = 0
    var upperIndex = lastIndex
    var currentIndex: Int

    while (lowerIndex <= upperIndex) {
        currentIndex = floor((lowerIndex + upperIndex) / 2.0).toInt()
        val currentValue = stops[currentIndex]
        val nextValue = stops.getOrNull(currentIndex + 1)

        if (currentValue <= input) {
            if (currentIndex == lastIndex || (nextValue != null && input < nextValue)) {
                return currentIndex
            }
            lowerIndex = currentIndex + 1
        } else if (currentValue > input) {
            upperIndex = currentIndex - 1
        } else {
            throw RuntimeError("Input is not a number.", key)
        }
    }

    return 0
}
