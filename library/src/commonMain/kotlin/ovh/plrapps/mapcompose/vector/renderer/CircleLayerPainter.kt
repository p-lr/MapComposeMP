package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import ovh.plrapps.mapcompose.vector.renderer.utils.CIRCLE_MIN_STROKE_WIDTH
import ovh.plrapps.mapcompose.vector.renderer.utils.CircleVertexGate
import ovh.plrapps.mapcompose.vector.renderer.utils.circleGradientStops
import ovh.plrapps.mapcompose.vector.renderer.utils.withTranslate
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.CircleLayer
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsColor
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsDoubleList
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsFloat
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsString
import kotlin.math.abs
import kotlin.math.max

/**
 * Draws `circle` layers.
 *
 * Follows maplibre-gl-js `src/shaders/glsl/circle.fragment.glsl`: one disc reaching out to
 * `circle-radius + circle-stroke-width`, whose alpha and colour along the radius are the shader's
 * two `smoothstep`s. [ovh.plrapps.mapcompose.vector.renderer.utils.circleGradientStops] holds that
 * profile and samples it into a radial gradient's colour stops, which is the only way to reach a
 * fragment shader from Compose; a circle that is neither blurred nor stroked skips it and is drawn
 * as a plain solid disc, since the profile is then flat but for Skia's own coverage antialiasing.
 *
 * Two consequences worth naming. `circle-blur` feathers the **whole** disc, stroke included, rather
 * than the fill alone -- upstream's band is a fraction of `radius + stroke_width`. And a
 * `circle-radius` of 0 with a positive `circle-stroke-width` is not empty: the shader's `color_t` is
 * then 1 everywhere, so it draws a solid disc of `circle-stroke-width` in the stroke colour.
 *
 * A circle is drawn at **every vertex** of the feature, whatever its geometry type -- upstream's
 * `CircleBucket.addFeature` does not look at the type either, which is how a `circle` layer over a
 * line or polygon source renders. Which vertices this tile draws is [CircleVertexGate]'s decision:
 * its own, plus the gathered neighbouring tiles' own vertices whose disc reaches in, because a tile
 * is rasterized into its own bitmap here and cannot spill a disc over its neighbour the way
 * upstream's viewport-wide framebuffer does.
 *
 * `circle-pitch-scale` and `circle-pitch-alignment` are inert: both describe how a circle reacts to
 * camera pitch, and MapComposeMP has no pitch.
 */
class CircleLayerPainter : BaseLayerPainter<CircleLayer>() {
    override suspend fun paint(
        canvas: DrawScope,
        feature: Tile.Feature,
        style: CircleLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        featureKey: String?
    ) = paint(
        canvas, feature, style, canvasSize, extent, zoom, featureProperties, actualZoom, featureKey,
        gate = CircleVertexGate(),
    )

    /**
     * Draws [feature]'s vertices that [gate] accepts, at the offset the gate carries.
     *
     * The offset is what lets a *neighbouring* tile's features be drawn into this one: they are
     * decoded in their own tile's space and shifted by a whole tile from there, rather than through
     * a canvas translate, so the gate can test the shifted disc against this tile in one place.
     */
    suspend fun paint(
        canvas: DrawScope,
        feature: Tile.Feature,
        style: CircleLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        featureKey: String?,
        gate: CircleVertexGate,
    ) {
        val paint = style.paint

        val density = canvas.density

        val radius = (paint.circleRadius.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.CIRCLE_RADIUS.toFloat()).coerceAtLeast(0f) * density

        val color = paint.circleColor?.processAsColor(featureProperties, actualZoom)
            ?: StyleSpecDefaults.CIRCLE_COLOR
        val opacity = paint.circleOpacity.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.CIRCLE_OPACITY.toFloat()
        val blur = paint.circleBlur.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.CIRCLE_BLUR.toFloat()
        val strokeWidthStyle = paint.circleStrokeWidth.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.CIRCLE_STROKE_WIDTH.toFloat()
        /* The 0.01 is upstream's, and it is a *style* pixel test -- it gates only the stroke colour,
         * never the geometry, which reaches `radius + stroke_width` whatever the width. */
        val hasStroke = strokeWidthStyle >= CIRCLE_MIN_STROKE_WIDTH
        val strokeWidth = strokeWidthStyle.coerceAtLeast(0f) * density
        val strokeColor = paint.circleStrokeColor?.processAsColor(featureProperties, actualZoom)
            ?: StyleSpecDefaults.CIRCLE_STROKE_COLOR
        val strokeOpacity = paint.circleStrokeOpacity.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.CIRCLE_STROKE_OPACITY.toFloat()
        val translate = paint.circleTranslate.processAsDoubleList(featureProperties, actualZoom)
            ?: StyleSpecDefaults.CIRCLE_TRANSLATE
        val translateAnchor = paint.circleTranslateAnchor?.processAsString(featureProperties, actualZoom)
            ?: StyleSpecDefaults.CIRCLE_TRANSLATE_ANCHOR

        val totalRadius = radius + strokeWidth
        if (totalRadius <= 0f) return

        /* The paint is read before the vertices are filtered because the filter needs the disc's
         * own size, and every one of these is a per-feature value, not a per-vertex one. */
        val translateX = (translate.getOrNull(0) ?: 0.0).toFloat() * density
        val translateY = (translate.getOrNull(1) ?: 0.0).toFloat() * density
        val reach = (totalRadius + max(abs(translateX), abs(translateY))).toDouble()
        val points = geometryDecoders
            .decodeVertices(feature.geometry, extent = extent, canvasSize = canvasSize)
            .filter { gate.accepts(it.x, it.y, canvasSize, reach) }
        if (points.isEmpty()) return

        val fillColor = color.withOpacity(opacity)

        /* Flat profile: `color_t` is 0 everywhere and `opacity_t` only carries the shader's
         * one-pixel faux-antialiasing, which is what Skia's coverage antialiasing already is. */
        if (blur <= 0f && !hasStroke) {
            canvas.withTranslate(translate, translateAnchor) {
                for (point in points) {
                    drawCircle(
                        color = fillColor,
                        radius = totalRadius,
                        center = point.centeredOn(gate),
                        style = Fill,
                    )
                }
            }
            return
        }

        /* A radial gradient is positioned in draw-scope coordinates rather than relative to the
         * shape it fills, so the brush itself has to be rebuilt per circle -- but its colour stops
         * do not depend on the centre, and a feature is commonly hundreds of vertices. */
        val stops = circleGradientStops(
            radius = radius,
            strokeWidth = strokeWidth,
            blur = blur,
            fill = fillColor,
            stroke = strokeColor.withOpacity(strokeOpacity),
            hasStroke = hasStroke,
        )

        canvas.withTranslate(translate, translateAnchor) {
            for (point in points) {
                val center = point.centeredOn(gate)
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = stops,
                        center = center,
                        radius = totalRadius,
                    ),
                    radius = totalRadius,
                    center = center,
                    style = Fill,
                )
            }
        }
    }
}

/** The vertex's centre on this tile's canvas, carrying [gate]'s whole-tile offset. */
private fun Point.centeredOn(gate: CircleVertexGate): Offset =
    Offset((x + gate.offsetX).toFloat(), (y + gate.offsetY).toFloat())
