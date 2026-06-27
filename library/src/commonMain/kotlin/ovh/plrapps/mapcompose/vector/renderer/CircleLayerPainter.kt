package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.CircleLayer
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsFloat
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsColor

class CircleLayerPainter : BaseLayerPainter<CircleLayer>() {
    override suspend fun paint(
        canvas: DrawScope,
        feature: Tile.Feature,
        style: CircleLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: Map<String, Any?>?,
        actualZoom: Double,
        featureKey: String?
    ) {
        if (feature.type != Tile.GeomType.POINT) return

        val paint = style.paint ?: return
        val path = createPath(feature, canvasSize, extent) ?: return

        val circleColor = paint.circleColor?.processAsColor(featureProperties, zoom) ?: Color.Black
        val circleOpacity = paint.circleOpacity.processAsFloat(featureProperties, zoom) ?: 1f
        val circleRadius = paint.circleRadius.processAsFloat(featureProperties, zoom) ?: 5f
        val circleStrokeWidth = paint.circleStrokeWidth.processAsFloat(featureProperties, zoom) ?: 0f
        val circleStrokeColor = paint.circleStrokeColor?.processAsColor(featureProperties, zoom) ?: Color.Black

        canvas.drawPath(
            path = path,
            color = circleColor.copy(alpha = circleOpacity)
        )

        if (circleStrokeWidth > 0f) {
            canvas.drawPath(
                path = path,
                color = circleStrokeColor.copy(alpha = circleOpacity),
                style = Stroke(
                    width = circleStrokeWidth,
                    pathEffect = null
                )
            )
        }
    }
} 