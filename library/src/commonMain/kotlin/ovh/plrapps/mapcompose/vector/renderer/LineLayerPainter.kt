package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ovh.plrapps.mapcompose.vector.data.SpriteManager
import ovh.plrapps.mapcompose.vector.renderer.utils.PatternBrushCache
import ovh.plrapps.mapcompose.vector.renderer.utils.SHARP_CORNER_OFFSET
import ovh.plrapps.mapcompose.vector.renderer.utils.DashRun
import ovh.plrapps.mapcompose.vector.renderer.utils.blur2
import ovh.plrapps.mapcompose.vector.renderer.utils.dashPattern
import ovh.plrapps.mapcompose.vector.renderer.utils.dashRuns
import ovh.plrapps.mapcompose.vector.renderer.utils.drawLineMesh
import ovh.plrapps.mapcompose.vector.renderer.utils.lineInset
import ovh.plrapps.mapcompose.vector.renderer.utils.lineOutset
import ovh.plrapps.mapcompose.vector.renderer.utils.tessellateLine
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
 * Follows maplibre-gl-js `src/render/draw_line.ts`: the feature is tessellated into the same
 * triangle ribbon upstream's `line_bucket.ts` builds ([tessellateLine]) and drawn with
 * `Canvas.drawVertices`, each vertex carrying the alpha `line.fragment.glsl` would have computed
 * there ([ovh.plrapps.mapcompose.vector.renderer.utils.lineAlpha]). That is what makes `line-blur`,
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
 *   `renderer/utils/LineMeshDraw.kt`. A patterned line is therefore still wallpapered in canvas
 *   space rather than mapped along the line, and a dashed one still gets Compose's dash effect.
 * - **A round cap or round join** is real fan geometry rather than upstream's per-fragment radial
 *   distance; the chord error is bounded at a quarter pixel. See
 *   [ovh.plrapps.mapcompose.vector.renderer.utils.roundStepCount].
 * - **`line-offset`** now moves the cross-section along the join normal, miter length included, as
 *   the vertex shader does. Very sharp inner corners still fold over -- upstream folds too.
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

        canvas.withTranslate(translate, translateAnchor) {
            for (polyline in polylines) {
                if (polyline.size < 4) continue

                if (patternBrush != null) {
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
                    continue
                }

                val totalLength = polylineLength(polyline)
                val points = if (gradient != null) resampleForGradient(polyline, totalLength) else polyline

                // A dashed line is cut into its painted runs before tessellation, so every run is an
                // ordinary line with the layer's own cap at each end.
                val runs = if (dash != null) {
                    dashRuns(points, dash)
                } else {
                    listOf(DashRun(points, 0f))
                }

                for (run in runs) {
                    val meshes = tessellateLine(
                        points = run.points,
                        pointCount = run.points.size / 2,
                        // A cut run is an open line even when the feature is a polygon ring.
                        isPolygon = isPolygon && dash == null,
                        join = join,
                        cap = cap,
                        miterLimit = miterLimit,
                        roundLimit = roundLimit,
                        outset = outset,
                        inset = inset,
                        blur2 = blur2,
                        offset = lineOffset,
                        sharpCornerOffset = sharpCornerOffset,
                        startDistance = run.startDistance,
                        totalLength = totalLength,
                    )

                    for (mesh in meshes) {
                        if (gradient != null) {
                            drawLineMesh(mesh, lineOpacity) { progress ->
                                gradient.processAsGradientColor(progress.toDouble(), actualZoom)
                                    ?: StyleSpecDefaults.LINE_COLOR
                            }
                        } else {
                            drawLineMesh(mesh, lineColor, lineOpacity)
                        }
                    }
                }
            }
        }
    }

    /**
     * Decoded polylines for a feature, memoised in the renderer's shared path cache.
     *
     * Every visible tile re-renders its features on each zoom step, and command-stream decoding is
     * the expensive half of that; the cache is keyed by tile, layer and feature id so a feature that
     * spans several style layers is decoded once per layer, as before. Each polyline is `x, y`
     * interleaved in canvas pixels, which is what the tessellator reads.
     */
    private suspend fun cachedPolylines(
        feature: Tile.Feature,
        canvasSize: Int,
        extent: Int,
        featureKey: String?,
    ): List<FloatArray> {
        if (featureKey == null || pathCache == null || mutex == null) {
            return decodePolylines(feature, canvasSize, extent)
        }
        @Suppress("UNCHECKED_CAST")
        mutex.withLock { pathCache.get(featureKey) as? List<FloatArray> }?.let { return it }
        val decoded = decodePolylines(feature, canvasSize, extent)
        mutex.withLock { pathCache.put(featureKey, decoded) }
        return decoded
    }

    private fun decodePolylines(
        feature: Tile.Feature,
        canvasSize: Int,
        extent: Int,
    ): List<FloatArray> = when (feature.type) {
        Tile.GeomType.LINESTRING ->
            geometryDecoders.decodeLine(feature.geometry, canvasSize = canvasSize, extent = extent)

        Tile.GeomType.POLYGON ->
            geometryDecoders.decodePolygons(feature.geometry, canvasSize = canvasSize, extent = extent)
                .flatten()

        else -> emptyList()
    }.filter { it.size >= 2 }.map { points ->
        FloatArray(points.size * 2) { i ->
            if (i % 2 == 0) points[i / 2].first else points[i / 2].second
        }
    }

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
