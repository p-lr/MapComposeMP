package ovh.plrapps.mapcompose.ui.state

import kotlinx.coroutines.channels.Channel
import ovh.plrapps.mapcompose.core.Tile

internal actual fun Tile.sendToRecycle(recycleChannel: Channel<Tile>) {
    // Empty on purpose
}

internal actual fun Tile.performRecycle() {
    // Empty on purpose
}