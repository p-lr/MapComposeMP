package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import ovh.plrapps.mapcompose.vector.data.SpriteManager
import ovh.plrapps.mapcompose.vector.renderer.utils.PatternBrushCache
import ovh.plrapps.mapcompose.vector.renderer.utils.SHARP_CORNER_OFFSET
import ovh.plrapps.mapcompose.vector.renderer.utils.blur2
import ovh.plrapps.mapcompose.vector.renderer.utils.dashPattern
import ovh.plrapps.mapcompose.vector.renderer.utils.dashRuns
import ovh.plrapps.mapcompose.vector.renderer.utils.LineBatch
import ovh.plrapps.mapcompose.vector.renderer.utils.LineMeshBuilder
import ovh.plrapps.mapcompose.vector.renderer.utils.lineInset
import ovh.plrapps.mapcompose.vector.renderer.utils.lineOutset
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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Draws `line` layers.
 *
 * Follows maplibre-gl-js `src/render/draw_line.ts`: the feature is tessellated into the same
 * triangle ribbon upstream's `line_bucket.ts` builds ([LineMeshBuilder]) and drawn with
 * `Canvas.drawVertices` -- a whole layer per call where it fits, see [LineBatch] -- each vertex
 * carrying the alpha `line.fragment.glsl` would have computed there ([ovh.plrapps.mapcompose.vector.renderer.utils.lineAlpha]). That is what makes `line-blur`,
 * `line-gradient`, `line-offset` and `line-round-limit` behave as upstream does rather than as a
 * Compose `Stroke` can express -- see `renderer/utils/LineShading.kt` for why a mesh reproduces the
 * fragment shader exactly rather than approximating it.
 *
 * Line and polygon features share one path: a polygon is tessellated ring by ring, which is what
 * upstream's line bucket does with a polygon's exterior and interior rings.
 *
 * **Divergences:**
 *
 * - **`line-pattern` and `line-dasharray`** keep the old `Stroke` path. Both need the paint to carry
 *   a shader or a path effect, and a shader in the paint is exactly what would make `drawVertices`
 *   behave differently on Android, whose Compose actual drops the blend mode -- see
 *   `renderer/utils/LineBatch.kt`. A patterned line is therefore still wallpapered in canvas
 *   space rather than mapped along the line, and a dashed one still gets Compose's dash effect.
 * - **A round cap or round join** is real fan geometry rather than upstream's per-fragment radial
 *   distance; the chord error is bounded at a quarter pixel. See
 *   [ovh.plrapps.mapcompose.vector.renderer.utils.roundStepCount].
 * - **`line-offset`** now moves the cross-section along the join normal, miter length included, as
 *   the vertex shader does. Very sharp inner corners still fold over -- upstream folds too.
 */
class LineLayerPainter(
    private val spriteManager: SpriteManager? = null,
    private val patternBrushes: PatternBrushCache = PatternBrushCache(),
) : BaseLayerPainter<LineLayer>() {

    /** Every mesh of the layer, drawn in as few calls as possible. See [LineBatch]. */
    private val batch = LineBatch()

    /* What the builder's sink colours the chunk it is handed with: the feature being tessellated.
     * The builder is finished at the end of every feature, so a chunk never spans two of them. */
    private var sinkScope: DrawScope? = null
    private var sinkOpacity = 1f
    private var sinkColor: Color? = null
    private var sinkColorAt: ((Float) -> Color)? = null
    private var sinkDx = 0f
    private var sinkDy = 0f

    private val builder = LineMeshBuilder { mesh ->
        batch.add(sinkScope!!, mesh, sinkOpacity, sinkColor, sinkColorAt, sinkDx, sinkDy)
    }

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
        append(canvas, feature, style, canvasSize, extent, zoom, featureProperties, actualZoom)
        flush(canvas)
    }

    /** Draws every mesh [append] has queued. The layer must call it before it ends. */
    fun flush(canvas: DrawScope) {
        batch.flush(canvas)
    }

    /**
     * Tessellates one feature into the layer's batch, without drawing it yet -- see [flush]. A
     * patterned line, which cannot be batched, flushes what is queued and is drawn at once.
     *
     * [decoded] is the feature's geometry when the caller already has it -- `TileGeometry` decodes
     * a tile layer once for every style layer that draws it -- and [cull], when given, skips a
     * feature whose ribbon cannot reach the part of the canvas being drawn.
     */
    suspend fun append(
        canvas: DrawScope,
        feature: Tile.Feature,
        style: LineLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        decoded: List<FloatArray>? = null,
        cull: FeatureCull? = null,
    ) {
        if (feature.type != Tile.GeomType.LINESTRING && feature.type != Tile.GeomType.POLYGON) return

        val paint = style.paint
        val layout = style.layout
        val density = canvas.density

        val polylines = decoded ?: decodePolylines(feature, canvasSize, extent)
        if (polylines.isEmpty()) return

        val lineColor = paint.lineColor?.processAsColor(featureProperties, actualZoom)
            ?: StyleSpecDefaults.LINE_COLOR
        val lineOpacity = paint.lineOpacity.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.LINE_OPACITY.toFloat()
        val lineWidth = (paint.lineWidth.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.LINE_WIDTH.toFloat()) * density
        val lineGapWidth = (paint.lineGapWidth.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.LINE_GAP_WIDTH.toFloat()) * density
        val lineBlur = (paint.lineBlur.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.LINE_BLUR.toFloat()) * density
        val lineOffset = (paint.lineOffset.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.LINE_OFFSET.toFloat()) * density
        val translate = paint.lineTranslate.processAsDoubleList(featureProperties, actualZoom)
            ?: StyleSpecDefaults.LINE_TRANSLATE
        val translateAnchor = paint.lineTranslateAnchor?.processAsString(featureProperties, actualZoom)
            ?: StyleSpecDefaults.LINE_TRANSLATE_ANCHOR

        val cap = layout.lineCap?.processAsString(featureProperties, actualZoom)
            ?: StyleSpecDefaults.LINE_CAP
        val join = layout.lineJoin?.processAsString(featureProperties, actualZoom)
            ?: StyleSpecDefaults.LINE_JOIN
        val miterLimit = layout.lineMiterLimit.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.LINE_MITER_LIMIT.toFloat()
        val roundLimit = layout.lineRoundLimit.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.LINE_ROUND_LIMIT.toFloat()

        if (lineWidth <= 0f && lineGapWidth <= 0f) return

        if (cull != null) {
            /* How far the ribbon can reach past the geometry: its outer edge, pushed sideways by the
             * offset, stretched at a miter by at most the miter limit, and moved by the translate.
             * Every term is an upper bound, so a feature skipped here would have drawn nothing. */
            val outer = lineOutset(lineGapWidth, lineWidth, density) + abs(lineOffset)
            val shift = translate.sumOf { abs(it) }.toFloat() * density
            if (!cull.reaches(outer * max(miterLimit, 2f) + shift + 1f)) return
        }

        val dash = paint.lineDasharray
            .processAsDoubleList(featureProperties, actualZoom)
            ?.let { dashPattern(it, lineWidth) }

        val patternName = paint.linePattern?.processAsString(featureProperties, actualZoom)
        val patternBrush = patternBrushes.get(spriteManager, patternName)
        val gradient = paint.lineGradient

        val inset = lineInset(lineGapWidth, density)
        val outset = lineOutset(lineGapWidth, lineWidth, density)
        val blur2 = blur2(lineBlur, density)
        val isPolygon = feature.type == Tile.GeomType.POLYGON
        // Upstream states its sharp-corner offset in pixels of a 512 px tile.
        val sharpCornerOffset = SHARP_CORNER_OFFSET * canvasSize / 512f

        if (patternBrush != null) {
            // Drawn now, so whatever the batch holds has to be drawn first to keep the order.
            batch.flush(canvas)
            canvas.withTranslate(translate, translateAnchor) {
                for (polyline in polylines) {
                    if (polyline.size < 4) continue
                    strokeLegacy(
                        points = polyline,
                        brush = patternBrush,
                        opacity = lineOpacity,
                        width = lineWidth,
                        gapWidth = lineGapWidth,
                        offset = lineOffset,
                        cap = strokeCapOf(cap),
                        join = strokeJoinOf(join),
                        miterLimit = miterLimit,
                    )
                }
            }
            return
        }
        if (lineOpacity <= 0f) return

        sinkScope = canvas
        sinkOpacity = lineOpacity
        sinkColor = if (gradient != null) null else lineColor
        sinkColorAt = if (gradient != null) {
            { progress ->
                gradient.processAsGradientColor(progress.toDouble(), actualZoom) ?: StyleSpecDefaults.LINE_COLOR
            }
        } else {
            null
        }
        sinkDx = (translate.getOrNull(0) ?: 0.0).toFloat() * density
        sinkDy = (translate.getOrNull(1) ?: 0.0).toFloat() * density

        for (polyline in polylines) {
            if (polyline.size < 4) continue

            val totalLength = polylineLength(polyline)
            val points = if (gradient != null) resampleForGradient(polyline, totalLength) else polyline

            // A dashed line is cut into its painted runs before tessellation, so every run is an
            // ordinary line with the layer's own cap at each end.
            if (dash != null) {
                for (run in dashRuns(points, dash)) {
                    builder.configure(outset, inset, blur2, lineOffset, run.startDistance, totalLength)
                    // A cut run is an open line even when the feature is a polygon ring.
                    builder.addLine(
                        run.points, run.points.size / 2, false, join, cap, miterLimit, roundLimit,
                        sharpCornerOffset,
                    )
                }
            } else {
                builder.configure(outset, inset, blur2, lineOffset, 0f, totalLength)
                builder.addLine(
                    points, points.size / 2, isPolygon, join, cap, miterLimit, roundLimit, sharpCornerOffset,
                )
            }
        }
        // The chunk is coloured by this feature's sink state, so it must not outlive the feature.
        builder.finish()
        sinkScope = null
    }

    private fun decodePolylines(
        feature: Tile.Feature,
        canvasSize: Int,
        extent: Int,
    ): List<FloatArray> = when (feature.type) {
        Tile.GeomType.LINESTRING ->
            geometryDecoders.decodeLineArrays(feature.geometry, canvasSize = canvasSize, extent = extent)

        Tile.GeomType.POLYGON ->
            geometryDecoders.decodePolygonRingArrays(feature.geometry, canvasSize = canvasSize, extent = extent)

        else -> emptyList()
    }.filter { it.size >= 4 }

    private fun polylineLength(points: FloatArray): Float {
        var total = 0f
        for (i in 1 until points.size / 2) {
            total += hypot(points[2 * i] - points[2 * i - 2], points[2 * i + 1] - points[2 * i - 1])
        }
        return total
    }

    /**
     * Inserts vertices so that `line-gradient` is evaluated as often as upstream samples it.
     *
     * Upstream renders the expression into a 256-pixel ramp texture (`src/util/color_ramp.ts`) and
     * samples it per fragment with linear filtering, so a vertex at every `1 / 255` of the feature's
     * length plus Gouraud interpolation between them is that same function. A long straight run is
     * a single MVT segment, and colouring it from its endpoints alone would band it.
     */
    private fun resampleForGradient(points: FloatArray, totalLength: Float): FloatArray {
        if (totalLength <= 0f) return points
        val spacing = totalLength / (GRADIENT_RAMP_RESOLUTION - 1)
        if (spacing <= 0f) return points

        val resampled = ArrayList<Float>(points.size * 2)
        resampled.add(points[0])
        resampled.add(points[1])
        for (i in 1 until points.size / 2) {
            val ax = points[2 * i - 2]
            val ay = points[2 * i - 1]
            val bx = points[2 * i]
            val by = points[2 * i + 1]
            val length = hypot(bx - ax, by - ay)
            val steps = (length / spacing).toInt()
            for (step in 1 until steps) {
                val t = step.toFloat() / steps
                resampled.add(ax + (bx - ax) * t)
                resampled.add(ay + (by - ay) * t)
            }
            resampled.add(bx)
            resampled.add(by)
        }
        return FloatArray(resampled.size) { resampled[it] }
    }

    /**
     * The pre-mesh stroke, kept for `line-pattern` only.
     *
     * A pattern lives in a `ShaderBrush`, and a shader in the paint is exactly what would make
     * `drawVertices` behave differently on Android, so a patterned line still goes through
     * `drawPath`. Everything the mesh does better -- the blur falloff, per-vertex gradients, a
     * miter-compensated offset -- is unavailable here, which is the trade documented on the class.
     */
    private fun DrawScope.strokeLegacy(
        points: FloatArray,
        brush: Brush,
        opacity: Float,
        width: Float,
        gapWidth: Float,
        offset: Float,
        cap: StrokeCap,
        join: StrokeJoin,
        miterLimit: Float,
    ) {
        if (width <= 0f || opacity <= 0f) return

        // line-gap-width draws a casing: the styled line becomes two lines flanking a gap of that
        // width, rather than one line down the middle.
        val offsets = if (gapWidth > 0f) {
            val half = (gapWidth + width) / 2f
            listOf(offset - half, offset + half)
        } else {
            listOf(offset)
        }

        val stroke = Stroke(
            width = width,
            cap = cap,
            join = join,
            miter = miterLimit,
        )

        for (sideOffset in offsets) {
            val path = pathOf(if (sideOffset == 0f) points else offsetPolyline(points, sideOffset))
                ?: continue
            drawPath(path = path, brush = brush, alpha = opacity, style = stroke)
        }
    }

    private fun pathOf(points: FloatArray): Path? {
        if (points.size < 4) return null
        val path = Path()
        path.moveTo(points[0], points[1])
        for (i in 1 until points.size / 2) {
            path.lineTo(points[2 * i], points[2 * i + 1])
        }
        return path
    }

    /** Shifts a polyline sideways by [offset] pixels, along the average of the adjacent normals. */
    private fun offsetPolyline(points: FloatArray, offset: Float): FloatArray {
        val count = points.size / 2
        if (count < 2) return points

        val normals = FloatArray((count - 1) * 2)
        for (i in 1 until count) {
            val dx = points[2 * i] - points[2 * i - 2]
            val dy = points[2 * i + 1] - points[2 * i - 1]
            val length = hypot(dx, dy)
            if (length > 0f) {
                normals[2 * (i - 1)] = -dy / length
                normals[2 * (i - 1) + 1] = dx / length
            }
        }

        val result = FloatArray(points.size)
        for (i in 0 until count) {
            var nx: Float
            var ny: Float
            val hasBefore = i > 0
            val hasAfter = i < count - 1
            if (hasBefore && hasAfter) {
                val sx = normals[2 * (i - 1)] + normals[2 * i]
                val sy = normals[2 * (i - 1) + 1] + normals[2 * i + 1]
                val length = hypot(sx, sy)
                if (length > 0f) {
                    nx = sx / length
                    ny = sy / length
                } else {
                    nx = normals[2 * i]
                    ny = normals[2 * i + 1]
                }
            } else if (hasAfter) {
                nx = normals[0]
                ny = normals[1]
            } else {
                nx = normals[2 * (count - 2)]
                ny = normals[2 * (count - 2) + 1]
            }
            result[2 * i] = points[2 * i] + nx * offset
            result[2 * i + 1] = points[2 * i + 1] + ny * offset
        }
        return result
    }

    private fun strokeCapOf(value: String): StrokeCap =
        when (value) {
            "round" -> StrokeCap.Round
            "square" -> StrokeCap.Square
            else -> StrokeCap.Butt
        }

    private fun strokeJoinOf(value: String): StrokeJoin =
        when (value) {
            "bevel" -> StrokeJoin.Bevel
            "round" -> StrokeJoin.Round
            else -> StrokeJoin.Miter
        }

    private companion object {
        /** The width of upstream's `line-gradient` ramp texture, in `src/util/color_ramp.ts`. */
        const val GRADIENT_RAMP_RESOLUTION = 256
    }
}
