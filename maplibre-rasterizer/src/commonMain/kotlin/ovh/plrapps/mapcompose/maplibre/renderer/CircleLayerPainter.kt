package ovh.plrapps.mapcompose.maplibre.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import ovh.plrapps.mapcompose.maplibre.renderer.collision.CollisionDetector
import ovh.plrapps.mapcompose.maplibre.spec.Tile
import ovh.plrapps.mapcompose.maplibre.spec.style.CircleLayer

class CircleLayerPainter : BaseLayerPainter<CircleLayer>() {
    override fun paint(
        canvas: DrawScope,
        feature: Tile.Feature,
        style: CircleLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: Map<String, Any?>?,
        actualZoom: Double
    ) {
        if (feature.type != Tile.GeomType.POINT) return

        val paint = style.paint ?: return
        val path = createPath(feature, canvasSize, extent) ?: return

        val circleColor = paint.circleColor?.process(featureProperties, zoom) ?: Color.Black
        val circleOpacity = paint.circleOpacity?.process(featureProperties, zoom)?.toFloat() ?: 1f
        val circleRadius = paint.circleRadius?.process(featureProperties, zoom) ?: 5f
        val circleStrokeWidth = paint.circleStrokeWidth?.process(featureProperties, zoom)?.toFloat() ?: 0f
        val circleStrokeColor = paint.circleStrokeColor?.process(featureProperties, zoom) ?: Color.Black

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