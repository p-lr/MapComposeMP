package ovh.plrapps.mapcompose.vector.ui.symbols

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import ovh.plrapps.mapcompose.vector.renderer.LabelArt
import ovh.plrapps.mapcompose.vector.symbol.LabelPath
import ovh.plrapps.mapcompose.vector.symbol.SymbolInstance
import ovh.plrapps.mapcompose.vector.symbol.PathInterpolator
import ovh.plrapps.mapcompose.vector.symbol.PathPoint
import ovh.plrapps.mapcompose.vector.symbol.SymbolProjection
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
     * The glyph placements for [art] along [path], or null to draw it straight.
     *
     * Null means one of: the path does not bend enough to be worth it, the label has no per-glyph
     * quads (a wrapped or vertical label, or the Compose fallback), or a glyph ran off an end of
     * the path. The last is upstream's `notEnoughRoom`, where it hides the label instead; hiding it
     * here would flicker, because the placement pass has already decided the label fits and would
     * keep re-placing it.
     */
    fun place(
        art: LabelArt.Glyphs,
        path: LabelPath,
        textScale: Float,
        alongOffset: Float,
        perpendicular: Float,
        keepUpright: Boolean,
        curveEpsilonPx: Float,
        mapRotationDeg: Float,
    ): List<PathPoint>? {
        val quads = art.quads ?: return null
        if (bendOf(path.points) < curveEpsilonPx) return null

        val interpolator = PathInterpolator(path.points.map { it.x to it.y })
        val anchorDistance = interpolator.distanceTo(path.anchorIndex) + alongOffset * textScale
        val offsets = FloatArray(quads.size) { quads[it].alongOffset * textScale }

        val forward = SymbolProjection.placeGlyphsAlongPath(
            path = path.points,
            anchorDistance = anchorDistance,
            offsets = offsets,
            perpendicular = perpendicular * textScale,
            flip = false,
        ) ?: return null

        if (!keepUpright || !readsBackwards(forward, mapRotationDeg)) return forward
        return SymbolProjection.placeGlyphsAlongPath(
            path = path.points,
            anchorDistance = anchorDistance,
            offsets = offsets,
            perpendicular = perpendicular * textScale,
            flip = true,
        ) ?: forward
    }

    /**
     * The label's own stretch of line, projected to the canvas, or null when the label does not
     * follow one.
     *
     * The stretch was cut by the layout pass (`SymbolInstance.Text.globalLine`), so this is a
     * projection of a handful of vertices and no search: a merged road can carry hundreds of them
     * and this runs for every line label on every frame.
     */
    fun pathFor(
        symbol: SymbolInstance.Text,
        project: (Point) -> Offset,
    ): LabelPath? {
        val line = symbol.globalLine ?: return null
        if (line.size < 2) return null
        return LabelPath(
            points = line.map { project(it) },
            anchorIndex = symbol.globalAnchorIndex.coerceIn(0, line.size - 1),
        )
    }

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
    fun bendOf(points: List<Offset>): Float {
        if (points.size < 3) return 0f
        val first = points.first()
        val last = points.last()
        val dx = last.x - first.x
        val dy = last.y - first.y
        val length = sqrt(dx * dx + dy * dy)
        if (length <= 0f) return 0f
        var worst = 0f
        for (i in 1 until points.size - 1) {
            val p = points[i]
            /* Twice the triangle's area over its base is the height, i.e. the distance to the
             * chord's *line*; the vertices are between the ends, so that is the distance wanted. */
            val distance = abs((p.x - first.x) * dy - (p.y - first.y) * dx) / length
            if (distance > worst) worst = distance
        }
        return worst
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
        translate(point.x, point.y) {
            rotate(degrees = point.angleDeg, pivot = Offset.Zero) {
                art.drawGlyph(this, quads[i], alpha = alpha, scale = scale)
            }
        }
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
