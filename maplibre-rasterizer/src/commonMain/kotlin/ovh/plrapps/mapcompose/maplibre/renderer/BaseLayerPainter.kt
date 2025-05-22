package ovh.plrapps.mapcompose.maplibre.renderer

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import ovh.plrapps.mapcompose.maplibre.renderer.collision.CollisionDetector
import ovh.plrapps.mapcompose.maplibre.spec.Tile
import ovh.plrapps.mapcompose.maplibre.spec.style.Layer

abstract class BaseLayerPainter<T : Layer> {
    protected val geometryDecoders = GeometryDecoders()

    abstract fun paint(
        canvas: DrawScope,
        collisionDetector: CollisionDetector,
        feature: Tile.Feature,
        style: T,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: Map<String, Any?>?
    )

    protected fun createPath(
        feature: Tile.Feature,
        canvasSize: Int,
        extent: Int
    ): Path? {
        return when (feature.type) {
            Tile.GeomType.POLYGON -> {
                val rings = geometryDecoders.decodePolygon(feature.geometry, canvasSize = canvasSize, extent = extent)
                if (rings.isNotEmpty()) {
                    geometryDecoders.createPolygonPath(rings)
                } else null
            }
            Tile.GeomType.LINESTRING -> geometryDecoders.createLineStringPath(
                geometryDecoders.decodeLine(feature.geometry, canvasSize = canvasSize, extent = extent).flatten()
            )
            Tile.GeomType.POINT -> null // TODO: Implement point rendering
            else -> null
        }
    }
} 