package ovh.plrapps.mapcompose.vector.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ovh.plrapps.mapcompose.vector.renderer.Symbol

internal class SymbolState {
    var symbols by mutableStateOf<List<Symbol>>(emptyList())
    var visiblePhases by mutableStateOf(0..0)
}