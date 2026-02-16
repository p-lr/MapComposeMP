package ovh.plrapps.mapcompose.core

import kotlinx.io.RawSource

/**
 * Defines how vector tiles should be fetched. It must be supplied as part of the configuration of
 * MapCompose.
 *
 * The [styleUrl] property is used to identify the style of the vector tiles.
 *
 * The [loadResources] method is used to load resources (e.g, stylesheets) required to render the vector
 * tiles.
 *
 * The [getTileStream] method implementation may suspend, but it isn't required (e.g, it isn't
 * required to switch context using withContext(Dispatcher.IO) { .. }) as MapCompose does that
 * already. The [getTileStream] method is declared using the suspend modifier, as it is sometimes
 * useful to provide an implementation which suspends.
 *
 * The [getTileStream] method is invoked with the following parameters:
 * - [tileUrl]: the url of the tile to fetch.
 * - [row]: the row index of the tile.
 * - [col]: the column index of the tile.
 * - [zoomLvl]: the zoom level of the tile.
 * [row], [col] and [zoomLvl] are can be used to implement caching strategies, because the [tileUrl] may change
 * when multiple servers are used.
 *
 * MapCompose leverages bitmap pooling to reduce the pressure on the garbage collector. However,
 * there's no tile caching by default - this is an implementation detail of the supplied
 * [VectorTileStreamProvider].
 *
 * If [getTileStream] returns null, the tile won't be rendered.
 * The library does not handle exceptions thrown from [getTileStream]. Such errors are treated as
 * unrecoverable failures.
 */
interface VectorTileStreamProvider {
    val styleUrl: String

    suspend fun loadResources(url: String): RawSource?

    suspend fun getTileStream(tileUrl: String, row: Int, col: Int, zoomLvl: Int): RawSource?
}