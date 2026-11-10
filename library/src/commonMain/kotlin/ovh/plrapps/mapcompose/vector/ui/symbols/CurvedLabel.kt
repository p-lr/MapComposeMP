package ovh.plrapps.mapcompose.vector.ui.symbols

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import ovh.plrapps.mapcompose.vector.data.glyphs.RenderedGlyph
import ovh.plrapps.mapcompose.vector.renderer.LabelArt
import ovh.plrapps.mapcompose.vector.symbol.LabelPath
import ovh.plrapps.mapcompose.vector.symbol.PathPoint
import ovh.plrapps.mapcompose.vector.renderer.Point
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A label that follows its line, glyph by glyph -- upstream's `placeGlyphsAlongLine`
 * (`src/symbol/projection.ts`), which it runs from `updateLineLabels` on **every** render.
 *
 * This happens at draw time for the same reason the size and the fade change do: a placement is
 * *held* for at least a fade duration (see `Placement.stillRecent`), so a bend baked at commit time
 * would step once per cycle through a pan or a pinch instead of following the road. The collision
 * pass already walks the same curve -- `Placement.circleChain` -- so what is drawn and what was
 * tested now describe one shape.
 *
 * Everything here works in the canvas space `SymbolComposer` establishes, which is rotate plus
 * translate and **no scale**: one unit is one screen pixel, and the map's bearing comes from the
 * enclosing transform rather than from any term here.
 */
internal object CurvedLabel {

    /**
     * Below this much bend a label is drawn as one blit at its chord's angle.
     *
     * Half a dp of deviation is under the width of the ink's antialiasing, so the two paths are
     * indistinguishable there -- and it is the gate that keeps the per-glyph path off the great
     * majority of line labels, which run straight over the stretch they occupy.
     */
    const val CURVE_EPSILON_DP: Float = 0.5f

    /**
     * The angle a straight label takes: its path's **chord**, not the anchor's own segment.
     *
     * Using the segment the anchor happens to sit on -- which is what the layout pass records --
     * would make the label jump the moment the map crosses [CURVE_EPSILON_DP] and the glyph path
     * takes over.
     */
    fun chordAngleDeg(path: LabelPath): Float {
        val first = path.points.first()
        val last = path.points.last()
        return atan2(last.y - first.y, last.x - first.x) * 180f / PI.toFloat()
    }

    /**
     * Whether the label would read right to left, so `text-keep-upright` has to turn it around.
     *
     * Upstream's `requiresOrientationChange`, which compares the first and last glyph in **screen**
     * space. This port's layout-time `makeTextUpright` cannot: it decides in the bucket's own space,
     * where the map's bearing is not yet known.
     *
     * The canvas these positions live in is map-aligned -- `SymbolComposer` rotates it as a whole --
     * so the bearing has to be applied here, which is what [mapRotationDeg] is for.
     */
    fun readsBackwards(placements: List<PathPoint>, mapRotationDeg: Float): Boolean {
        if (placements.size < 2) return false
        val first = placements.first()
        val last = placements.last()
        return readsBackwards(Offset(first.x, first.y), Offset(last.x, last.y), mapRotationDeg)
    }

    /** As above, for a label drawn straight between the two ends of its path. */
    fun readsBackwards(first: Offset, last: Offset, mapRotationDeg: Float): Boolean {
        val radians = mapRotationDeg * PI.toFloat() / 180f
        val dx = last.x - first.x
        val dy = last.y - first.y
        // Compose's rotate is clockwise, so screen x is `x cos - y sin`.
        return dx * cos(radians) - dy * sin(radians) < 0f
    }

    /** The largest distance from an interior vertex of [points] to the chord of the whole path. */
    fun bendOf(points: List<Offset>): Float =
        bendOf(FloatArray(points.size) { points[it].x }, FloatArray(points.size) { points[it].y }, points.size)

    /** [bendOf] over the first [count] points of two coordinate arrays. */
    fun bendOf(xs: FloatArray, ys: FloatArray, count: Int): Float {
        if (count < 3) return 0f
        val firstX = xs[0]
        val firstY = ys[0]
        val dx = xs[count - 1] - firstX
        val dy = ys[count - 1] - firstY
        val length = sqrt(dx * dx + dy * dy)
        if (length <= 0f) return 0f
        var worst = 0f
        for (i in 1 until count - 1) {
            /* Twice the triangle's area over its base is the height, i.e. the distance to the
             * chord's *line*; the vertices are between the ends, so that is the distance wanted. */
            val distance = abs((xs[i] - firstX) * dy - (ys[i] - firstY) * dx) / length
            if (distance > worst) worst = distance
        }
        return worst
    }
}

/**
 * One line label's projected path and the glyph positions along it, for the frame being drawn.
 *
 * The draw pass owns a single instance and reuses it for every line label of every frame. This used
 * to be a list of projected points, a `LabelPath`, two `PathInterpolator`s over lists of pairs, an
 * offsets array and one or two lists of `PathPoint` -- per label, per frame, on the main thread. The
 * arithmetic is unchanged: [placeGlyphs] is `SymbolProjection.placeGlyphsAlongPath` over arrays,
 * which `LabelPathScratchTest` holds it to.
 */
internal class LabelPathScratch {
    private var xs = FloatArray(16)
    private var ys = FloatArray(16)
    private var distances = FloatArray(16)

    /** How many points the path has. */
    var size = 0
        private set

    /** Where the label's anchor sits in the path. */
    var anchorIndex = 0
        private set

    private var glyphX = FloatArray(32)
    private var glyphY = FloatArray(32)
    private var glyphAngle = FloatArray(32)

    /** How many glyphs the last successful [placeGlyphs] placed. */
    var glyphCount = 0
        private set

    fun x(index: Int): Float = xs[index]
    fun y(index: Int): Float = ys[index]
    fun glyphX(index: Int): Float = glyphX[index]
    fun glyphY(index: Int): Float = glyphY[index]
    fun glyphAngleDeg(index: Int): Float = glyphAngle[index]

    /**
     * Projects the label's own stretch of line -- `SymbolInstance.Text.globalLine`, cut by the
     * layout pass -- to the canvas, and says whether there is a path at all.
     *
     * A point lands at `point * world - origin`, the same arithmetic the anchor is projected with.
     * It is a projection of a handful of vertices and no search: a merged road can carry hundreds
     * of them and this runs for every line label on every frame.
     */
    fun load(
        line: List<Point>,
        anchorIndex: Int,
        worldWidth: Double,
        worldHeight: Double,
        originX: Double,
        originY: Double,
    ): Boolean {
        val count = line.size
        if (count < 2) return false
        if (xs.size < count) {
            xs = FloatArray(count * 2)
            ys = FloatArray(count * 2)
            distances = FloatArray(count * 2)
        }
        for (i in 0 until count) {
            val point = line[i]
            xs[i] = (point.x * worldWidth - originX).toFloat()
            ys[i] = (point.y * worldHeight - originY).toFloat()
        }
        size = count
        this.anchorIndex = anchorIndex.coerceIn(0, count - 1)
        glyphCount = 0
        return true
    }

    /** See [CurvedLabel.bendOf]. */
    fun bend(): Float = CurvedLabel.bendOf(xs, ys, size)

    /** See [CurvedLabel.chordAngleDeg]. */
    fun chordAngleDeg(): Float =
        atan2(ys[size - 1] - ys[0], xs[size - 1] - xs[0]) * 180f / PI.toFloat()

    /** See [CurvedLabel.readsBackwards], for a label drawn straight between the path's two ends. */
    fun chordReadsBackwards(mapRotationDeg: Float): Boolean = CurvedLabel.readsBackwards(
        Offset(xs[0], ys[0]), Offset(xs[size - 1], ys[size - 1]), mapRotationDeg,
    )

    /**
     * Places [quads] along the loaded path, and says whether the label is to be drawn that way.
     *
     * False means one of: the path does not bend enough to be worth it, or a glyph ran off an end
     * of the path. The last is upstream's `notEnoughRoom`, where it hides the label instead; hiding
     * it here would flicker, because the placement pass has already decided the label fits and
     * would keep re-placing it. The caller draws the label straight in either case.
     */
    fun placeGlyphs(
        quads: List<RenderedGlyph>,
        textScale: Float,
        alongOffset: Float,
        perpendicular: Float,
        keepUpright: Boolean,
        curveEpsilonPx: Float,
        mapRotationDeg: Float,
    ): Boolean {
        glyphCount = 0
        if (quads.isEmpty() || bend() < curveEpsilonPx) return false

        distances[0] = 0f
        for (i in 1 until size) {
            val dx = xs[i] - xs[i - 1]
            val dy = ys[i] - ys[i - 1]
            distances[i] = distances[i - 1] + sqrt(dx * dx + dy * dy)
        }
        if (glyphX.size < quads.size) {
            glyphX = FloatArray(quads.size * 2)
            glyphY = FloatArray(quads.size * 2)
            glyphAngle = FloatArray(quads.size * 2)
        }

        val anchorDistance = distances[anchorIndex] + alongOffset * textScale
        val normal = perpendicular * textScale
        if (!walk(quads, textScale, anchorDistance, normal, flip = false)) return false
        glyphCount = quads.size

        if (!keepUpright || quads.size < 2) return true
        val last = quads.size - 1
        val backwards = CurvedLabel.readsBackwards(
            Offset(glyphX[0], glyphY[0]), Offset(glyphX[last], glyphY[last]), mapRotationDeg,
        )
        if (!backwards) return true
        // Turned around; and if that runs off the path, the forward placement stands.
        if (!walk(quads, textScale, anchorDistance, normal, flip = true)) {
            walk(quads, textScale, anchorDistance, normal, flip = false)
        }
        return true
    }

    /** `SymbolProjection.placeGlyphsAlongPath`, into the glyph arrays; false when a glyph falls off. */
    private fun walk(
        quads: List<RenderedGlyph>,
        textScale: Float,
        anchorDistance: Float,
        normal: Float,
        flip: Boolean,
    ): Boolean {
        val length = distances[size - 1]
        val direction = if (flip) -1f else 1f
        for (i in quads.indices) {
            val distance = anchorDistance + direction * (quads[i].alongOffset * textScale)
            if (distance < 0f || distance > length) return false

            // `PathInterpolator.at`: the first vertex at or past the distance ends the segment.
            var current = 1
            while (distances[current] < distance && current < size - 1) current++
            val previous = current - 1
            val segmentLength = distances[current] - distances[previous]
            val t = if (segmentLength > 0f) (distance - distances[previous]) / segmentLength else 0f

            var angle = atan2(ys[current] - ys[previous], xs[current] - xs[previous]) * 180f / PI.toFloat()
            if (flip) angle += 180f
            var x = xs[previous] * (1f - t) + xs[current] * t
            var y = ys[previous] * (1f - t) + ys[current] * t
            if (normal != 0f) {
                // `PathPoint.movedAlongNormal`.
                val radians = angle * PI.toFloat() / 180f
                x -= sin(radians) * normal
                y += cos(radians) * normal
            }
            glyphX[i] = x
            glyphY[i] = y
            glyphAngle[i] = angle
        }
        return true
    }
}

/**
 * Draws a label glyph by glyph at [placements], which are absolute canvas positions and angles.
 *
 * Each glyph is drawn about its own centre, which is what [PathPoint] locates and what
 * [LabelArt.Glyphs.drawGlyph] expects -- upstream positions its quads the same way, by
 * `glyphOffset = shapedGlyph.x + halfAdvance`.
 */
internal fun DrawScope.drawTextAlongPath(
    art: LabelArt.Glyphs,
    placements: List<PathPoint>,
    alpha: Float,
    scale: Float,
) {
    val quads = art.quads ?: return
    for (i in quads.indices) {
        val point = placements.getOrNull(i) ?: return
        drawGlyphAt(art, quads[i], point.x, point.y, point.angleDeg, alpha, scale)
    }
}

/** As above, at the positions [LabelPathScratch.placeGlyphs] left in [scratch]. */
internal fun DrawScope.drawTextAlongPath(
    art: LabelArt.Glyphs,
    quads: List<RenderedGlyph>,
    scratch: LabelPathScratch,
    alpha: Float,
    scale: Float,
) {
    for (i in 0 until minOf(quads.size, scratch.glyphCount)) {
        drawGlyphAt(art, quads[i], scratch.glyphX(i), scratch.glyphY(i), scratch.glyphAngleDeg(i), alpha, scale)
    }
}

private fun DrawScope.drawGlyphAt(
    art: LabelArt.Glyphs,
    quad: RenderedGlyph,
    x: Float,
    y: Float,
    angleDeg: Float,
    alpha: Float,
    scale: Float,
) {
    withTransform({
        translate(x, y)
        rotate(degrees = angleDeg, pivot = Offset.Zero)
    }) {
        art.drawGlyph(this, quad, alpha = alpha, scale = scale)
    }
}

/**
 * Draws a line label as one blit at [angleDeg], the path's chord.
 *
 * The straight half of the same decision [CurvedLabel.place] makes, kept here so both halves
 * position the label identically -- about the centre of its own box, at the canvas position the
 * caller has already translated to.
 */
internal fun DrawScope.drawTextAtAngle(
    art: LabelArt,
    angleDeg: Float,
    alpha: Float,
    scale: Float,
) {
    val center = Offset(art.width * scale / 2f, art.height * scale / 2f)
    rotate(degrees = angleDeg, pivot = center) {
        art.draw(this, Offset.Zero, alpha = alpha, scale = scale)
    }
}
