package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import ovh.plrapps.mapcompose.vector.renderer.utils.withTranslate
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.CircleLayer
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsColor
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsDoubleList
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsFloat
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsString

/**
 * Draws `circle` layers.
 *
 * Follows maplibre-gl-js `src/shaders/circle.fragment.glsl`: a filled disc of `circle-radius`, then
 * a `circle-stroke-width` ring drawn outside it, with `circle-blur` feathering the fill inwards.
 *
 * `circle-pitch-scale` and `circle-pitch-alignment` are read but inert: both describe how a circle
 * reacts to camera pitch, and MapComposeMP has no pitch.
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
    ) {
        if (feature.type != Tile.GeomType.POINT) return

        val paint = style.paint ?: return
        val points = geometryDecoders.decodePoint(feature.geometry, extent = extent, canvasSize = canvasSize)
        if (points.isEmpty()) return

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

        val fillColor = color.withOpacity(opacity)

        canvas.withTranslate(translate, translateAnchor) {
            for (point in points) {
                val center = Offset(point.x.toFloat(), point.y.toFloat())

                // The gradient has to be rebuilt per circle: a radial gradient is positioned in
                // draw-scope coordinates, not relative to the shape it fills.
                val fillBrush = blurBrush(fillColor, blur, radius, center)
                if (fillBrush != null) {
                    drawCircle(brush = fillBrush, radius = radius, center = center, style = Fill)
                } else {
                    drawCircle(color = fillColor, radius = radius, center = center, style = Fill)
                }

                if (strokeWidth > 0f) {
                    // The stroke sits outside the fill, so its centreline is half a stroke out.
                    drawCircle(
                        color = strokeColor.withOpacity(strokeOpacity),
                        radius = radius + strokeWidth / 2f,
                        center = center,
                        style = Stroke(width = strokeWidth)
                    )
                }
            }
        }
    }

    /**
     * The feathered fill for a non-zero `circle-blur`, or `null` when the circle is solid.
     *
     * The shader keeps the disc at full opacity out to `1 / (1 + blur)` of the radius and fades to
     * nothing at the edge, so a blur of 1 leaves only the centre point opaque.
     */
    private fun blurBrush(color: Color, blur: Float, radius: Float, center: Offset): Brush? {
        if (blur <= 0f) return null
        val solidStop = (1f / (1f + blur)).coerceIn(0f, 0.999f)
        return Brush.radialGradient(
            colorStops = arrayOf(
                0f to color,
                solidStop to color,
                1f to color.copy(alpha = 0f),
            ),
            center = center,
            radius = radius,
        )
    }
}
