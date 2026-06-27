package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.FillLayer
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsFloat
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsColor
import ovh.plrapps.mapcompose.vector.utils.LruCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FillLayerPainter(
    private val pathCache: LruCache<String, Any>? = null,
    private val mutex: Mutex? = null
) : BaseLayerPainter<FillLayer>() {
    override suspend fun paint(
        canvas: DrawScope,
        feature: Tile.Feature,
        style: FillLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: Map<String, Any?>?,
        actualZoom: Double,
        featureKey: String?
    ) {
        if (feature.type != Tile.GeomType.POLYGON) return

        val paint = style.paint ?: return

        val path: Path? = if (featureKey != null && pathCache != null && mutex != null) {
            mutex.withLock {
                pathCache.get(featureKey) as? Path
            } ?: createPath(feature, canvasSize, extent)?.also {
                mutex.withLock {
                    pathCache.put(featureKey, it)
                }
            }
        } else {
            createPath(feature, canvasSize, extent)
        }

        if (path == null) return

        // FIXME: It's broken previous layers
        if (paint.fillPattern != null) {
            return
        }
        val fillColor = paint.fillColor?.processAsColor(featureProperties, actualZoom) ?: Color.Transparent
        val fillOpacity = paint.fillOpacity.processAsFloat(featureProperties, actualZoom) ?: 1f
        val fillOutlineColor = paint.fillOutlineColor?.processAsColor(featureProperties, actualZoom) ?: Color.Transparent

        canvas.drawPath(
            path = path,
            color = fillColor.copy(alpha = fillOpacity)
        )

        if (paint.fillOutlineColor != null) {
            canvas.drawPath(
                path = path,
                color = fillOutlineColor,
                style = Stroke(
                    width = canvas.density,
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
