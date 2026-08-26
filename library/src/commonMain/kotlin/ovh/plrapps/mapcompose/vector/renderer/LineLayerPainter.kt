package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ovh.plrapps.mapcompose.vector.data.SpriteManager
import ovh.plrapps.mapcompose.vector.renderer.utils.PatternBrushCache
import ovh.plrapps.mapcompose.vector.renderer.utils.withTranslate
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.LineLayer
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsColor
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsDoubleList
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsFloat
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsGradientColor
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsString
import ovh.plrapps.mapcompose.vector.utils.LruCache
import kotlin.math.hypot

/**
 * Draws `line` layers.
 *
 * Follows maplibre-gl-js `src/render/draw_line.ts` and the `line*` shaders, within what a Compose
 * `Stroke` can express. Line and polygon features share one path: a polygon is stroked ring by
 * ring, which is what upstream's line bucket does with a polygon's exterior and interior rings.
 *
 * Known divergences from upstream, all forced by stroking on the CPU rather than tessellating:
 *
 * - **`line-round-limit`** is inert. Upstream uses it during tessellation to degrade a round join
 *   to a miter at shallow angles; Compose picks the join for the whole stroke, so there is nothing
 *   to degrade per-join.
 * - **`line-blur`** is approximated with concentric strokes, widest and faintest first, rather than
 *   the shader's per-fragment falloff.
 * - **`line-gradient`** is approximated by splitting the feature into fixed-length sub-segments and
 *   evaluating the gradient once per segment; Compose cannot colour a stroke per vertex.
 * - **`line-offset`** offsets vertices along the averaged normal of their adjacent segments instead
 *   of recomputing the join geometry, so very sharp corners self-intersect slightly.
 */
class LineLayerPainter(
    private val pathCache: LruCache<String, Any>? = null,
    private val mutex: Mutex? = null,
    private val spriteManager: SpriteManager? = null,
    private val patternBrushes: PatternBrushCache = PatternBrushCache(),
) : BaseLayerPainter<LineLayer>() {

    override suspend fun paint(
        canvas: DrawScope,
        feature: Tile.Feature,
        style: LineLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        featureKey: String?
    ) {
        if (feature.type != Tile.GeomType.LINESTRING && feature.type != Tile.GeomType.POLYGON) return

        val paint = style.paint
        val layout = style.layout
        val density = canvas.density

        val polylines = cachedPolylines(feature, canvasSize, extent, featureKey)
        if (polylines.isEmpty()) return

        val lineColor = paint?.lineColor?.processAsColor(featureProperties, actualZoom)
            ?: StyleSpecDefaults.LINE_COLOR
        val lineOpacity = paint?.lineOpacity.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.LINE_OPACITY.toFloat()
        val lineWidth = (paint?.lineWidth.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.LINE_WIDTH.toFloat()) * density
        val lineGapWidth = (paint?.lineGapWidth.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.LINE_GAP_WIDTH.toFloat()) * density
        val lineBlur = (paint?.lineBlur.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.LINE_BLUR.toFloat()) * density
        val lineOffset = (paint?.lineOffset.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.LINE_OFFSET.toFloat()) * density
        val translate = paint?.lineTranslate.processAsDoubleList(featureProperties, actualZoom)
            ?: StyleSpecDefaults.LINE_TRANSLATE
        val translateAnchor = paint?.lineTranslateAnchor?.processAsString(featureProperties, actualZoom)
            ?: StyleSpecDefaults.LINE_TRANSLATE_ANCHOR

        val cap = strokeCapOf(layout?.lineCap?.processAsString(featureProperties, actualZoom))
        val join = strokeJoinOf(layout?.lineJoin?.processAsString(featureProperties, actualZoom))
        val miterLimit = layout?.lineMiterLimit.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.LINE_MITER_LIMIT.toFloat()

        if (lineWidth <= 0f && lineGapWidth <= 0f) return

        // Spec: dasharray lengths "are later scaled by the line width".
        val dashIntervals = paint?.lineDasharray
            .processAsDoubleList(featureProperties, actualZoom)
            ?.let { dashIntervals(it, lineWidth) }

        val patternName = paint?.linePattern?.processAsString(featureProperties, actualZoom)
        val patternBrush = patternBrushes.get(spriteManager, patternName)
        val gradient = paint?.lineGradient

        // line-gap-width draws a casing: the styled line becomes two lines flanking a gap of that
        // width, rather than one line down the middle.
        val offsets = if (lineGapWidth > 0f) {
            val half = (lineGapWidth + lineWidth) / 2f
            listOf(lineOffset - half, lineOffset + half)
        } else {
            listOf(lineOffset)
        }

        canvas.withTranslate(translate, translateAnchor) {
            for (polyline in polylines) {
                for (offset in offsets) {
                    val points = if (offset == 0f) polyline else offsetPolyline(polyline, offset)
                    if (points.size < 2) continue

                    if (gradient != null) {
                        strokeGradient(
                            points = points,
                            gradient = gradient,
                            zoom = actualZoom,
                            opacity = lineOpacity,
                            width = lineWidth,
                            cap = cap,
                            join = join,
                            miterLimit = miterLimit,
                            dashIntervals = dashIntervals,
                        )
                        continue
                    }

                    val path = pathOf(points)
                    strokeWithBlur(
                        path = path,
                        color = lineColor,
                        brush = patternBrush,
                        opacity = lineOpacity,
                        width = lineWidth,
                        blur = lineBlur,
                        cap = cap,
                        join = join,
                        miterLimit = miterLimit,
                        dashIntervals = dashIntervals,
                    )
                }
            }
        }
    }

    /**
     * Decoded polylines for a feature, memoised in the renderer's shared path cache.
     *
     * Every visible tile re-renders its features on each zoom step, and command-stream decoding is
     * the expensive half of that; the cache is keyed by tile, layer and feature id so a feature that
     * spans several style layers is decoded once per layer, as before.
     */
    private suspend fun cachedPolylines(
        feature: Tile.Feature,
        canvasSize: Int,
        extent: Int,
        featureKey: String?,
    ): List<List<Pair<Float, Float>>> {
        if (featureKey == null || pathCache == null || mutex == null) {
            return decodePolylines(feature, canvasSize, extent)
        }
        @Suppress("UNCHECKED_CAST")
        mutex.withLock { pathCache.get(featureKey) as? List<List<Pair<Float, Float>>> }?.let { return it }
        val decoded = decodePolylines(feature, canvasSize, extent)
        mutex.withLock { pathCache.put(featureKey, decoded) }
        return decoded
    }

    private fun decodePolylines(
        feature: Tile.Feature,
        canvasSize: Int,
        extent: Int,
    ): List<List<Pair<Float, Float>>> = when (feature.type) {
        Tile.GeomType.LINESTRING ->
            geometryDecoders.decodeLine(feature.geometry, canvasSize = canvasSize, extent = extent)

        Tile.GeomType.POLYGON ->
            geometryDecoders.decodePolygons(feature.geometry, canvasSize = canvasSize, extent = extent)
                .flatten()

        else -> emptyList()
    }.filter { it.size >= 2 }

    /**
     * Draws the stroke, preceded by wider translucent passes when `line-blur` is set.
     *
     * The faint wide passes go first so the core stroke lands on top at full opacity; drawing them
     * in the other order darkens and thickens the line instead of feathering it.
     */
    private fun DrawScope.strokeWithBlur(
        path: Path,
        color: Color,
        brush: Brush?,
        opacity: Float,
        width: Float,
        blur: Float,
        cap: StrokeCap,
        join: StrokeJoin,
        miterLimit: Float,
        dashIntervals: FloatArray?,
    ) {
        if (blur > 0f) {
            val steps = BLUR_STEPS
            for (i in steps downTo 1) {
                val haloWidth = width + (blur * 2f * i) / steps
                val haloOpacity = opacity * (1f - i.toFloat() / (steps + 1))
                strokeOnce(path, color, null, haloOpacity, haloWidth, cap, join, miterLimit, dashIntervals)
            }
        }
        strokeOnce(path, color, brush, opacity, width, cap, join, miterLimit, dashIntervals)
    }

    private fun DrawScope.strokeOnce(
        path: Path,
        color: Color,
        brush: Brush?,
        opacity: Float,
        width: Float,
        cap: StrokeCap,
        join: StrokeJoin,
        miterLimit: Float,
        dashIntervals: FloatArray?,
    ) {
        if (width <= 0f || opacity <= 0f) return
        val stroke = Stroke(
            width = width,
            cap = cap,
            join = join,
            miter = miterLimit,
            pathEffect = dashIntervals?.let { PathEffect.dashPathEffect(intervals = it, phase = 0f) }
        )
        if (brush != null) {
            drawPath(path = path, brush = brush, alpha = opacity, style = stroke)
        } else {
            drawPath(path = path, color = color.withOpacity(opacity), style = stroke)
        }
    }

    /**
     * Strokes a feature in short sub-segments, re-evaluating `line-gradient` at each one.
     *
     * The feature is resampled at a fixed spacing rather than stroked vertex to vertex: a long
     * straight run is a single MVT segment, and evaluating the gradient once for it would paint the
     * whole run in one colour.
     */
    private fun DrawScope.strokeGradient(
        points: List<Pair<Float, Float>>,
        gradient: ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue<Color>,
        zoom: Double,
        opacity: Float,
        width: Float,
        cap: StrokeCap,
        join: StrokeJoin,
        miterLimit: Float,
        dashIntervals: FloatArray?,
    ) {
        var total = 0f
        for (i in 1 until points.size) {
            total += hypot(points[i].first - points[i - 1].first, points[i].second - points[i - 1].second)
        }
        if (total <= 0f) return

        var travelled = 0f
        for (i in 1 until points.size) {
            val from = points[i - 1]
            val to = points[i]
            val length = hypot(to.first - from.first, to.second - from.second)
            if (length <= 0f) continue

            val steps = (length / GRADIENT_SEGMENT_PX).toInt().coerceIn(1, MAX_GRADIENT_SEGMENTS)
            for (step in 0 until steps) {
                val t0 = step.toFloat() / steps
                val t1 = (step + 1).toFloat() / steps
                val a = lerp(from, to, t0)
                val b = lerp(from, to, t1)
                val progress = ((travelled + length * (t0 + t1) / 2f) / total).toDouble()
                val color = gradient.processAsGradientColor(progress, zoom) ?: StyleSpecDefaults.LINE_COLOR
                strokeOnce(pathOf(listOf(a, b)), color, null, opacity, width, cap, join, miterLimit, dashIntervals)
            }
            travelled += length
        }
    }

    private fun lerp(from: Pair<Float, Float>, to: Pair<Float, Float>, t: Float): Pair<Float, Float> =
        Pair(from.first + (to.first - from.first) * t, from.second + (to.second - from.second) * t)

    private fun pathOf(points: List<Pair<Float, Float>>): Path {
        val path = Path()
        path.moveTo(points[0].first, points[0].second)
        for (i in 1 until points.size) {
            path.lineTo(points[i].first, points[i].second)
        }
        return path
    }

    /**
     * Shifts a polyline sideways by [offset] pixels, positive to the right of travel.
     *
     * Each vertex moves along the average of the normals of the segments meeting there, which keeps
     * the offset continuous through joins without rebuilding them.
     */
    private fun offsetPolyline(
        points: List<Pair<Float, Float>>,
        offset: Float,
    ): List<Pair<Float, Float>> {
        if (points.size < 2) return points

        val normals = ArrayList<Pair<Float, Float>>(points.size - 1)
        for (i in 1 until points.size) {
            val dx = points[i].first - points[i - 1].first
            val dy = points[i].second - points[i - 1].second
            val len = hypot(dx, dy)
            normals.add(if (len > 0f) Pair(-dy / len, dx / len) else Pair(0f, 0f))
        }

        return points.indices.map { i ->
            val before = normals.getOrNull(i - 1)
            val after = normals.getOrNull(i)
            val nx: Float
            val ny: Float
            if (before != null && after != null) {
                val sx = before.first + after.first
                val sy = before.second + after.second
                val len = hypot(sx, sy)
                if (len > 0f) {
                    nx = sx / len
                    ny = sy / len
                } else {
                    nx = after.first
                    ny = after.second
                }
            } else {
                val n = after ?: before ?: Pair(0f, 0f)
                nx = n.first
                ny = n.second
            }
            Pair(points[i].first + nx * offset, points[i].second + ny * offset)
        }
    }

    private fun strokeCapOf(value: String?): StrokeCap =
        when (value ?: StyleSpecDefaults.LINE_CAP) {
            "round" -> StrokeCap.Round
            "square" -> StrokeCap.Square
            else -> StrokeCap.Butt
        }

    private fun strokeJoinOf(value: String?): StrokeJoin =
        when (value ?: StyleSpecDefaults.LINE_JOIN) {
            "bevel" -> StrokeJoin.Bevel
            "round" -> StrokeJoin.Round
            else -> StrokeJoin.Miter
        }

    private companion object {
        const val BLUR_STEPS = 4

        /** Sub-segment length a `line-gradient` is resampled at, in canvas pixels. */
        const val GRADIENT_SEGMENT_PX = 4f

        /** Upper bound on sub-segments per MVT segment, so a tile-spanning line stays cheap. */
        const val MAX_GRADIENT_SEGMENTS = 64
    }
}

/**
 * Converts a `line-dasharray` into Compose dash intervals.
 *
 * Spec: "The lengths are later scaled by the line width. To convert a dash length to pixels,
 * multiply the length by the current line width." An odd-length array describes a pattern that only
 * repeats after two passes, which Compose cannot express, so it is concatenated with itself --
 * upstream's `LineAtlas.getDashRanges` reaches the same repeat by halving the first and last part.
 *
 * Returns `null` for an array that yields no positive interval, so the caller draws a solid line
 * rather than an invisible one.
 */
internal fun dashIntervals(dashArray: List<Double>, lineWidthPx: Float): FloatArray? {
    if (dashArray.size < 2 || lineWidthPx <= 0f) return null
    val scaled = dashArray.map { (it * lineWidthPx).toFloat() }
    val intervals = if (scaled.size % 2 == 0) scaled else scaled + scaled
    if (intervals.any { it < 0f } || intervals.sum() <= 0f) return null
    return intervals.toFloatArray()
}
