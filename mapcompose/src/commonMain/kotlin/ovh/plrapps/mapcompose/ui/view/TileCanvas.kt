package ovh.plrapps.mapcompose.ui.view

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ovh.plrapps.mapcompose.core.*
import ovh.plrapps.mapcompose.ui.state.ZoomPanRotateState


@Composable
internal expect fun TileCanvas(
    modifier: Modifier,
    zoomPRState: ZoomPanRotateState,
    visibleTilesResolver: VisibleTilesResolver,
    tileSize: Int,
    alphaTick: Float,
    colorFilterProvider: ColorFilterProvider?,
    tilesToRender: List<Tile>,
    isFilteringBitmap: () -> Boolean,
)