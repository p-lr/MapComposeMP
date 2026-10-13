package ovh.plrapps.mapcompose.core

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.io.RawSource

/**
 * Defines how tiles should be fetched. It must be supplied as part of the configuration of
 * MapCompose.
 *
 * The [getTileStream] method implementation may suspend, but it isn't required (e.g, it isn't
 * required to switch context using withContext(Dispatcher.IO) { .. }) as MapCompose does that
 * already. The [getTileStream] method is declared using the suspend modifier, as it is sometimes
 * useful to provide an implementation which suspends.
 *
 * There's no tile caching by default - this is an implementation detail of the supplied
 * [TileStreamProvider].
 *
 * If [getTileStream] returns null, the tile won't be rendered.
 * The library does not handle exceptions thrown from [getTileStream]. Such errors are treated as
 * unrecoverable failures.
 */
fun interface TileStreamProvider {
    suspend fun getTileStream(row: Int, col: Int, zoomLvl: Int): RawSource?
}

/**
 * A tile provider that renders tiles itself and hands over the bitmap, rather than bytes for the
 * [TileCollector] to decode.
 *
 * The vector layer is one: it rasterizes into an `ImageBitmap`, and going through
 * [TileStreamProvider] meant encoding that bitmap into bytes only for the collector to decode it
 * straight back -- four to six full-size copies of every tile. [getTileStream] is never called on a
 * provider that implements this.
 */
internal interface BitmapTileProvider : TileStreamProvider {
    /**
     * Renders the tile at [row], [col], [zoomLvl], [subSample] levels of sub-sampling below its full
     * size.
     *
     * [isWanted] says whether the tile is still visible; a provider whose work is long should check
     * it and answer [BitmapTileResult.Skipped] as soon as it turns false.
     */
    suspend fun getTileBitmap(
        row: Int,
        col: Int,
        zoomLvl: Int,
        subSample: Int,
        isWanted: () -> Boolean,
    ): BitmapTileResult

    override suspend fun getTileStream(row: Int, col: Int, zoomLvl: Int): RawSource? = null
}

internal sealed interface BitmapTileResult {
    /**
     * A rendered tile. [bitmap] is a mutable, software bitmap the collector now owns: it may draw
     * other layers onto it, copy it to a hardware bitmap, and recycle it.
     */
    class Rendered(val bitmap: ImageBitmap) : BitmapTileResult

    /** Nothing to draw here -- a failure, or simply no data. Treated as a failed decode. */
    data object Empty : BitmapTileResult

    /** Abandoned because the tile stopped being wanted. Not a result: the tile is requested again if it reappears. */
    data object Skipped : BitmapTileResult
}
