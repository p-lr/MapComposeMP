package ovh.plrapps.mapcompose.vector.ui.symbols

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import ovh.plrapps.mapcompose.ui.layout.grid
import ovh.plrapps.mapcompose.vector.ui.state.SymbolState
import ovh.plrapps.mapcompose.ui.state.ZoomPanRotateState
import ovh.plrapps.mapcompose.vector.renderer.Symbol
import kotlin.math.ceil

@Composable
internal fun SymbolComposer(
    modifier: Modifier,
    zoomPRState: ZoomPanRotateState,
    symbolState: SymbolState
) {
    Canvas(
        modifier = modifier.fillMaxSize()
    ) {
        val x0 = ((ceil(zoomPRState.scrollX / grid) * grid)).toInt()
        val y0 = ((ceil(zoomPRState.scrollY / grid) * grid)).toInt()

        withTransform({
            rotate(
                degrees = zoomPRState.rotation,
                pivot = Offset(
                    x = zoomPRState.pivotX.toFloat(),
                    y = zoomPRState.pivotY.toFloat()
                )
            )
            translate(
                left = (-zoomPRState.scrollX + x0).toFloat(),
                top = (-zoomPRState.scrollY + y0).toFloat()
            )
        }) {
            for (phase in symbolState.visiblePhases) {
                val phaseOffsetPx = phase * zoomPRState.fullWidth * zoomPRState.scale
                for (symbol in symbolState.symbols.reversed()) {
                    val canvasX: Float
                    val canvasY: Float
                    if (symbol is Symbol.Text && symbol.spriteAnchorGlobal != null && symbol.textOffset != null) {
                        canvasX = (symbol.spriteAnchorGlobal.x * zoomPRState.fullWidth * zoomPRState.scale - x0 +
                                   symbol.textOffset.first + phaseOffsetPx).toFloat()
                        canvasY = (symbol.spriteAnchorGlobal.y * zoomPRState.fullHeight * zoomPRState.scale - y0 +
                                   symbol.textOffset.second).toFloat()
                    } else {
                        canvasX = (symbol.global.x * zoomPRState.fullWidth * zoomPRState.scale - x0 + phaseOffsetPx).toFloat()
                        canvasY = (symbol.global.y * zoomPRState.fullHeight * zoomPRState.scale - y0).toFloat()
                    }

                    val size = symbol.getInPixels()
                    val offsetX = size.width * symbol.align.x
                    val offsetY = size.height * symbol.align.y

                    withTransform({
                        translate(left = canvasX + offsetX, top = canvasY + offsetY)
                        if (symbol.viewportAligned) {
                            // Counter-rotate around the symbol's geographic reference point in local
                            // coords (= -offsetX, -offsetY), so the symbol stays horizontal on screen
                            // while its position still tracks the map-rotated viewport coordinate.
                            rotate(degrees = -zoomPRState.rotation, pivot = Offset(-offsetX, -offsetY))
                        }
                    }) {
                        symbol.draw(this)
                    }
                }
            }
        }
    }
}
