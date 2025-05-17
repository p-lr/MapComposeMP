package ovh.plrapps.mapcompose.maplibre.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import ovh.plrapps.mapcompose.maplibre.renderer.collision.CollisionDetector
import ovh.plrapps.mapcompose.maplibre.spec.Tile
import ovh.plrapps.mapcompose.maplibre.spec.style.FillLayer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class FillLayerPainter : BaseLayerPainter<FillLayer>() {
    override fun paint(
        canvas: DrawScope,
        collisionDetector: CollisionDetector,
        feature: Tile.Feature,
        style: FillLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: Map<String, Any?>?
    ) {
        if (feature.type != Tile.GeomType.POLYGON) return

        val paint = style.paint ?: return
        val path = createPath(feature, canvasSize, extent) ?: return

        val fillColor = paint.fillColor?.process(featureProperties, zoom) ?: Color.Black
        val fillOpacity = paint.fillOpacity?.process(featureProperties, zoom)?.toFloat() ?: 1f
        val fillOutlineColor = paint.fillOutlineColor?.process(featureProperties, zoom) ?: Color.Black

        canvas.drawPath(
            path = path,
            color = fillColor.copy(alpha = fillOpacity)
        )

        if (paint.fillOutlineColor != null) {
            canvas.drawPath(
                path = path,
                color = fillOutlineColor,
                style = Stroke(
                    width = 1f,
                    pathEffect = null
                )
            )
        }
    }

    private fun applyTranslation(path: Path, translate: JsonElement?): Path {
        if (translate == null) return path

        val offset = when (translate) {
            is JsonPrimitive -> {
                val value = translate.content.toFloatOrNull() ?: 0f
                Offset(value, value)
            }
            is JsonObject -> {
                val x = translate["x"]?.toString()?.toFloatOrNull() ?: 0f
                val y = translate["y"]?.toString()?.toFloatOrNull() ?: 0f
                Offset(x, y)
            }
            else -> Offset.Zero
        }

        if (offset == Offset.Zero) return path

        val translatedPath = Path()
        translatedPath.addPath(path, offset)
        return translatedPath
    }
}
