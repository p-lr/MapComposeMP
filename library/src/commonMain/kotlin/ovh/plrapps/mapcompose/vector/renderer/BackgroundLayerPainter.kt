package ovh.plrapps.mapcompose.vector.renderer

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.BackgroundLayer
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsFloat
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsColor

class BackgroundLayerPainter : BaseLayerPainter<BackgroundLayer>() {
    override suspend fun paint(
        canvas: DrawScope,
        feature: Tile.Feature,
        style: BackgroundLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        featureKey: String?
    ) {
        val paint = style.paint ?: return

        val backgroundColor = paint.backgroundColor?.processAsColor(featureProperties, zoom) ?: Color.White
        val opacity = paint.backgroundOpacity.processAsFloat(featureProperties, zoom) ?: 1f

        canvas.drawRect(
            color = backgroundColor.copy(alpha = opacity),
            size = canvas.size
        )
    }
} 