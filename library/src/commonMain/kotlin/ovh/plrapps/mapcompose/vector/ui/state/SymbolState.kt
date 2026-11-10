package ovh.plrapps.mapcompose.vector.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ovh.plrapps.mapcompose.vector.symbol.PlacementResult

internal class SymbolState {
    /** The last placement cycle's output: what to draw, and whether anything is still fading. */
    var placement by mutableStateOf(PlacementResult.Empty)
    var visiblePhases by mutableStateOf(0..0)
}
