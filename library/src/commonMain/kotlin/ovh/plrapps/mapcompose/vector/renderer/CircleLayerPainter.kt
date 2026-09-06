package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import ovh.plrapps.mapcompose.vector.renderer.utils.CircleVertexGate
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
 * Follows maplibre-gl-js `src/shaders/circle.fragment.glsl`: a filled disc of `circle-radius`, then
 * a `circle-stroke-width` ring drawn outside it, with `circle-blur` feathering the fill inwards.
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
            ?: StyleSpecDefaults.CIRCLE_RADIUS.toFloat()) * density
        if (radius <= 0f) return

        val color = paint.circleColor?.processAsColor(featureProperties, actualZoom)
            ?: StyleSpecDefaults.CIRCLE_COLOR
        val opacity = paint.circleOpacity.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.CIRCLE_OPACITY.toFloat()
        val blur = paint.circleBlur.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.CIRCLE_BLUR.toFloat()
        val strokeWidth = (paint.circleStrokeWidth.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.CIRCLE_STROKE_WIDTH.toFloat()) * density
        val strokeColor = paint.circleStrokeColor?.processAsColor(featureProperties, actualZoom)
            ?: StyleSpecDefaults.CIRCLE_STROKE_COLOR
        val strokeOpacity = paint.circleStrokeOpacity.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.CIRCLE_STROKE_OPACITY.toFloat()
        val translate = paint.circleTranslate.processAsDoubleList(featureProperties, actualZoom)
            ?: StyleSpecDefaults.CIRCLE_TRANSLATE
        val translateAnchor = paint.circleTranslateAnchor?.processAsString(featureProperties, actualZoom)
            ?: StyleSpecDefaults.CIRCLE_TRANSLATE_ANCHOR

        /* The paint is read before the vertices are filtered because the filter needs the disc's
         * own size, and every one of these is a per-feature value, not a per-vertex one. */
        val translateX = (translate.getOrNull(0) ?: 0.0).toFloat() * density
        val translateY = (translate.getOrNull(1) ?: 0.0).toFloat() * density
        val reach = (radius + strokeWidth + max(abs(translateX), abs(translateY))).toDouble()
        val points = geometryDecoders
            .decodeVertices(feature.geometry, extent = extent, canvasSize = canvasSize)
            .filter { gate.accepts(it.x, it.y, canvasSize, reach) }
        if (points.isEmpty()) return

        val fillColor = color.withOpacity(opacity)

        /* A radial gradient is positioned in draw-scope coordinates rather than relative to the
         * shape it fills, so the brush itself has to be rebuilt per circle -- but its colour stops
         * do not depend on the centre, and a feature is commonly hundreds of vertices. */
        val stops = blurColorStops(fillColor, blur)
        val strokePaintColor = strokeColor.withOpacity(strokeOpacity)
        val strokeStyle = if (strokeWidth > 0f) Stroke(width = strokeWidth) else null

        canvas.withTranslate(translate, translateAnchor) {
            for (point in points) {
                val center = Offset(
                    (point.x + gate.offsetX).toFloat(),
                    (point.y + gate.offsetY).toFloat(),
                )

                if (stops != null) {
                    val fillBrush = Brush.radialGradient(
                        colorStops = stops,
                        center = center,
                        radius = radius,
                    )
                    drawCircle(brush = fillBrush, radius = radius, center = center, style = Fill)
                } else {
                    drawCircle(color = fillColor, radius = radius, center = center, style = Fill)
                }

                if (strokeStyle != null) {
                    // The stroke sits outside the fill, so its centreline is half a stroke out.
                    drawCircle(
                        color = strokePaintColor,
                        radius = radius + strokeWidth / 2f,
                        center = center,
                        style = strokeStyle
                    )
                }
            }
        }
    }

    /**
     * The colour stops of the feathered fill for a non-zero `circle-blur`, or `null` when the
     * circle is solid.
     *
     * The shader keeps the disc at full opacity out to `1 / (1 + blur)` of the radius and fades to
     * nothing at the edge, so a blur of 1 leaves only the centre point opaque. Nothing here depends
     * on the circle's centre or radius, which is why the caller builds the stops once per feature
     * and only the `Brush` per vertex.
     */
    private fun blurColorStops(color: Color, blur: Float): Array<Pair<Float, Color>>? {
        if (blur <= 0f) return null
        val solidStop = (1f / (1f + blur)).coerceIn(0f, 0.999f)
        return arrayOf(
            0f to color,
            solidStop to color,
            1f to color.copy(alpha = 0f),
        )
    }
}
