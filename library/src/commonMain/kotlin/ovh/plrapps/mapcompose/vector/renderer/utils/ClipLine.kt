package ovh.plrapps.mapcompose.vector.renderer.utils

/**
 * The part of a multiline that lies inside the box `(x1, y1)..(x2, y2)`.
 *
 * A transcription of upstream's `clipLine` (`symbol/clip_line.ts`), which `symbol_layout.ts` wraps
 * the `symbol-placement: line` anchor walk in. An MVT layer is served with a buffer, so a road
 * reaches into its neighbours' tiles; without clipping, anchors land in that buffer and the same
 * stretch of road is labelled by two tiles. Clipping also puts a vertex *exactly* on the tile edge
 * wherever the line leaves it, which is what lets the anchor walk recognise a continued line and
 * offset its first label by half a spacing instead of half a label.
 *
 * `line-center` deliberately does not clip -- upstream: *"No clipping, multiple lines per feature
 * are allowed"*.
 */
fun clipLine(
    lines: List<List<Pair<Float, Float>>>,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
): List<List<Pair<Float, Float>>> {
    val clippedLines = mutableListOf<MutableList<Pair<Float, Float>>>()

    for (line in lines) {
        var clippedLine: MutableList<Pair<Float, Float>>? = null

        for (i in 0 until line.size - 1) {
            var p0 = line[i]
            var p1 = line[i + 1]

            if (p0.first < x1 && p1.first < x1) {
                continue
            } else if (p0.first < x1) {
                p0 = x1 to p0.second + (p1.second - p0.second) * ((x1 - p0.first) / (p1.first - p0.first))
            } else if (p1.first < x1) {
                p1 = x1 to p0.second + (p1.second - p0.second) * ((x1 - p0.first) / (p1.first - p0.first))
            }

            if (p0.second < y1 && p1.second < y1) {
                continue
            } else if (p0.second < y1) {
                p0 = p0.first + (p1.first - p0.first) * ((y1 - p0.second) / (p1.second - p0.second)) to y1
            } else if (p1.second < y1) {
                p1 = p0.first + (p1.first - p0.first) * ((y1 - p0.second) / (p1.second - p0.second)) to y1
            }

            if (p0.first >= x2 && p1.first >= x2) {
                continue
            } else if (p0.first >= x2) {
                p0 = x2 to p0.second + (p1.second - p0.second) * ((x2 - p0.first) / (p1.first - p0.first))
            } else if (p1.first >= x2) {
                p1 = x2 to p0.second + (p1.second - p0.second) * ((x2 - p0.first) / (p1.first - p0.first))
            }

            if (p0.second >= y2 && p1.second >= y2) {
                continue
            } else if (p0.second >= y2) {
                p0 = p0.first + (p1.first - p0.first) * ((y2 - p0.second) / (p1.second - p0.second)) to y2
            } else if (p1.second >= y2) {
                p1 = p0.first + (p1.first - p0.first) * ((y2 - p0.second) / (p1.second - p0.second)) to y2
            }

            val current = clippedLine
            if (current == null || current.last() != p0) {
                clippedLine = mutableListOf(p0).also { clippedLines.add(it) }
            }

            clippedLine!!.add(p1)
        }
    }

    return clippedLines
}
