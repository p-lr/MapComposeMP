package ovh.plrapps.mapcompose.vector.renderer.utils

/**
 * One line feature as [mergeLines] sees it: its label text, its geometry, and whatever the caller
 * needs to carry alongside.
 *
 * [geometry] is mutable and [lines] is null once the feature has been merged into another one --
 * that is upstream's `mergedFeatures[j].geometry = null` followed by a filter, kept because the
 * three-way merge depends on being able to void an entry it has already emitted.
 */
class MergeableFeature<T>(
    val text: String?,
    var lines: MutableList<MutableList<Pair<Float, Float>>>?,
    val value: T,
)

/**
 * Stitches adjacent line features carrying the same label into one line.
 *
 * A transcription of upstream's `merge_lines.ts`, which `SymbolBucket.populate` runs for every
 * `symbol-placement: line` layer: *"Merge adjacent lines with the same text to improve labeling.
 * It's better to place labels on one long line than on many short segments."*
 *
 * This matters more than it sounds. A road arrives from an MVT source as one feature per stretch
 * between intersections, so without merging the anchor walk restarts its phase on every fragment
 * and a street picks up a name every block instead of every `symbol-spacing` pixels.
 *
 * Only the first ring of each feature takes part, as upstream's does.
 */
fun <T> mergeLines(features: List<MergeableFeature<T>>): List<MergeableFeature<T>> {
    val leftIndex = mutableMapOf<String, Int>()
    val rightIndex = mutableMapOf<String, Int>()
    val mergedFeatures = mutableListOf<MergeableFeature<T>>()

    fun add(feature: MergeableFeature<T>) {
        mergedFeatures.add(feature)
    }

    fun mergeFromRight(
        leftKey: String,
        rightKey: String,
        geom: MutableList<MutableList<Pair<Float, Float>>>,
    ): Int {
        val i = rightIndex.remove(leftKey)!!
        rightIndex[rightKey] = i
        val target = mergedFeatures[i].lines!!
        target[0].removeAt(target[0].size - 1)
        target[0].addAll(geom[0])
        return i
    }

    fun mergeFromLeft(
        leftKey: String,
        rightKey: String,
        geom: MutableList<MutableList<Pair<Float, Float>>>,
    ): Int {
        val i = leftIndex.remove(rightKey)!!
        leftIndex[leftKey] = i
        val target = mergedFeatures[i].lines!!
        target[0].removeAt(0)
        target[0].addAll(0, geom[0])
        return i
    }

    fun getKey(
        text: String,
        geom: MutableList<MutableList<Pair<Float, Float>>>,
        onRight: Boolean = false,
    ): String {
        val point = if (onRight) geom[0].last() else geom[0].first()
        return "$text:${point.first}:${point.second}"
    }

    for (feature in features) {
        val geom = feature.lines
        val text = feature.text

        if (geom.isNullOrEmpty() || geom[0].size < 2 || text.isNullOrEmpty()) {
            add(feature)
            continue
        }

        val leftKey = getKey(text, geom)
        val rightKey = getKey(text, geom, onRight = true)

        if (leftKey in rightIndex && rightKey in leftIndex && rightIndex[leftKey] != leftIndex[rightKey]) {
            // Found lines with the same text adjacent to both ends of the current line, merge all three.
            val j = mergeFromLeft(leftKey, rightKey, geom)
            val i = mergeFromRight(leftKey, rightKey, mergedFeatures[j].lines!!)

            leftIndex.remove(leftKey)
            rightIndex.remove(rightKey)

            rightIndex[getKey(text, mergedFeatures[i].lines!!, onRight = true)] = i
            mergedFeatures[j].lines = null
        } else if (leftKey in rightIndex) {
            // Found a mergeable line adjacent to the start of the current line, merge.
            mergeFromRight(leftKey, rightKey, geom)
        } else if (rightKey in leftIndex) {
            // Found a mergeable line adjacent to the end of the current line, merge.
            mergeFromLeft(leftKey, rightKey, geom)
        } else {
            add(feature)
            leftIndex[leftKey] = mergedFeatures.size - 1
            rightIndex[rightKey] = mergedFeatures.size - 1
        }
    }

    return mergedFeatures.filter { it.lines != null }
}
