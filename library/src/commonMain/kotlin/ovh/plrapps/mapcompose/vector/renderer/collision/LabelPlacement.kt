package ovh.plrapps.mapcompose.vector.renderer.collision

import androidx.compose.ui.geometry.Rect
import ovh.plrapps.mapcompose.vector.utils.obb.OBB
import ovh.plrapps.mapcompose.vector.utils.obb.ObbPoint

/**
 * One symbol's collision box, and everything the placement pass needs to order it.
 *
 * [inLayerPriority] is the layer's `symbol-sort-key`; [hasSortKey] says whether the style actually
 * set one, which is what `symbol-z-order: auto` keys off. [zOrder] is that property's value.
 *
 * [circles], when non-null, *replaces* [obb] for collision -- upstream's `placeCollisionCircles`
 * against `placeCollisionBox` (`symbol/collision_index.ts`). A label laid along a line is a chain of
 * circles there, not one rectangle, because the straight envelope of a curve claims far more ground
 * than the label covers. Nothing fills this in yet: generating the chain needs the label's projected
 * path, which belongs to the symbol placement pass; [CollisionDetector] handles a chain wherever one
 * arrives. [obb] is still carried for drawing and for the broad-phase key of a box-shaped symbol.
 */
data class LabelPlacement(
    val text: String,
    val position: ObbPoint,
    val angle: Float,
    val bounds: Rect,
    val obb: OBB,
    val layerIndex: Int,
    val inLayerPriority: Double,
    val overlapMode: OverlapMode,
    val ignorePlacement: Boolean,
    val zOrder: String = ovh.plrapps.mapcompose.vector.spec.style.SYMBOL_Z_ORDER_AUTO,
    val hasSortKey: Boolean = false,
    val circles: List<CollisionCircle>? = null,
)
