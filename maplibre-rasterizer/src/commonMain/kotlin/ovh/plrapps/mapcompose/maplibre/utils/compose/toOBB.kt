package ovh.plrapps.mapcompose.maplibre.utils.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import ovh.plrapps.mapcompose.maplibre.utils.obb.OBB
import ovh.plrapps.mapcompose.maplibre.utils.obb.ObbPoint
import ovh.plrapps.mapcompose.maplibre.utils.obb.Size as ObbSize

/**
 * Converts Compose Rect to OBB with specified rotation
 * @param rotation rotation angle in degrees
 */
fun Rect.toOBB(rotation: Float = 0f): OBB {
    return OBB(
        center = ObbPoint(
            x = (left + right) / 2,
            y = (top + bottom) / 2
        ),
        size = ObbSize(
            width = right - left,
            height = bottom - top
        ),
        rotation = rotation
    )
}

/**
 * Converts Compose Offset to OBB Point
 */
fun Offset.toObbPoint(): ObbPoint {
    return ObbPoint(x = x, y = y)
}

/**
 * Converts Compose Size to OBB Size
 */
fun Size.toObbSize(): ObbSize {
    return ObbSize(width = width, height = height)
}

/**
 * Converts OBB Point to Compose Offset
 */
fun ObbPoint.toOffset(): Offset {
    return Offset(x = x, y = y)
}

/**
 * Converts OBB Size to Compose Size
 */
fun ObbSize.toSize(): Size {
    return Size(width = width, height = height)
} 