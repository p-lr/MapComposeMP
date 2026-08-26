package ovh.plrapps.mapcompose.vector.renderer

import ovh.plrapps.mapcompose.vector.spec.Tile

/**
 * Builders for MVT features and tiles.
 *
 * Upstream's bucket tests read binary `.mvt` fixtures; those are not published with the npm
 * package, and a binary fixture would tell us nothing about the command decoding that is actually
 * under test here. These build the command stream by hand instead, so the encoding a test exercises
 * is visible in the test.
 *
 * The helpers that actually paint a feature live in `skiaTest/PainterTestSupport.kt`, because
 * `ImageBitmap` needs a real graphics backend and Android unit tests do not have one.
 */
internal object Mvt {
    const val DEFAULT_EXTENT = 4096

    private const val MOVE_TO = 1
    private const val LINE_TO = 2
    private const val CLOSE_PATH = 7

    private fun command(id: Int, count: Int): Int = (count shl 3) or id

    private fun zigZag(n: Int): Int = (n shl 1) xor (n shr 31)

    /**
     * Encodes a sequence of rings/lines into an MVT command stream.
     *
     * [closeRings] appends a ClosePath after each ring, which is what distinguishes a polygon's
     * encoding from a line's.
     */
    private fun encode(parts: List<List<Pair<Int, Int>>>, closeRings: Boolean): List<Int> {
        val out = mutableListOf<Int>()
        var cursorX = 0
        var cursorY = 0
        for (part in parts) {
            if (part.isEmpty()) continue
            out += command(MOVE_TO, 1)
            out += zigZag(part[0].first - cursorX)
            out += zigZag(part[0].second - cursorY)
            cursorX = part[0].first
            cursorY = part[0].second

            val rest = part.drop(1)
            if (rest.isNotEmpty()) {
                out += command(LINE_TO, rest.size)
                for ((x, y) in rest) {
                    out += zigZag(x - cursorX)
                    out += zigZag(y - cursorY)
                    cursorX = x
                    cursorY = y
                }
            }
            if (closeRings) out += command(CLOSE_PATH, 1)
        }
        return out
    }

    /** A polygon feature. Each ring is given open; the ClosePath is added by the encoder. */
    fun polygonFeature(
        vararg rings: List<Pair<Int, Int>>,
        id: Long = 1L,
        tags: List<Int> = emptyList(),
    ): Tile.Feature = Tile.Feature(
        id = id,
        type = Tile.GeomType.POLYGON,
        geometry = encode(rings.toList(), closeRings = true),
        tags = tags,
    )

    /** A line feature; several parts make it a MultiLineString. */
    fun lineFeature(
        vararg lines: List<Pair<Int, Int>>,
        id: Long = 1L,
        tags: List<Int> = emptyList(),
    ): Tile.Feature = Tile.Feature(
        id = id,
        type = Tile.GeomType.LINESTRING,
        geometry = encode(lines.toList(), closeRings = false),
        tags = tags,
    )

    /** A point feature; several points make it a MultiPoint, encoded as one repeated MoveTo. */
    fun pointFeature(
        vararg points: Pair<Int, Int>,
        id: Long = 1L,
        tags: List<Int> = emptyList(),
    ): Tile.Feature {
        val out = mutableListOf(command(MOVE_TO, points.size))
        var cursorX = 0
        var cursorY = 0
        for ((x, y) in points) {
            out += zigZag(x - cursorX)
            out += zigZag(y - cursorY)
            cursorX = x
            cursorY = y
        }
        return Tile.Feature(id = id, type = Tile.GeomType.POINT, geometry = out, tags = tags)
    }

    /** A square ring wound clockwise -- an exterior ring in MVT terms. */
    fun clockwiseRing(left: Int, top: Int, right: Int, bottom: Int): List<Pair<Int, Int>> =
        listOf(left to top, right to top, right to bottom, left to bottom)

    /** A square ring wound counter-clockwise -- a hole. */
    fun counterClockwiseRing(left: Int, top: Int, right: Int, bottom: Int): List<Pair<Int, Int>> =
        clockwiseRing(left, top, right, bottom).reversed()

    fun layer(
        name: String = "test",
        features: List<Tile.Feature> = emptyList(),
        extent: Int = DEFAULT_EXTENT,
        keys: List<String> = emptyList(),
        values: List<Tile.Value> = emptyList(),
    ): Tile.Layer = Tile.Layer(
        version = 2,
        name = name,
        features = features,
        keys = keys,
        values = values,
        extent = extent,
    )

    fun tile(vararg layers: Tile.Layer): Tile = Tile(layers = layers.toList())

    fun stringValue(value: String): Tile.Value = Tile.Value(stringValue = value)

    fun numberValue(value: Double): Tile.Value = Tile.Value(doubleValue = value)
}
