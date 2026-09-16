package ovh.plrapps.mapcompose.vector.data

import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.plainDecimalString
import kotlin.math.PI

/**
 * The tile-address tokens a URL template can carry beyond `{z}/{x}/{y}`.
 *
 * Ports of the helpers `src/tile/tile_id.ts` inlines from the archived `@mapbox/whoots-js`
 * (ISC, Copyright (c) 2017 Mapbox), which `CanonicalTileID.url` calls before its `replace` chain:
 *
 * ```
 * const bbox = getTileBBox(this.x, this.y, this.z);
 * const quadkey = getQuadkey(this.z, this.x, this.y);
 * ```
 *
 * Both take the plain xyz `y`, *before* the `scheme` substitution mirrors it -- so neither depends
 * on the source's scheme, and [tileBBoxEpsg3857] does its own flip.
 */

private const val EPSG3857_RADIUS = 6378137.0
private const val EPSG3857_HALF_CIRCUMFERENCE = PI * EPSG3857_RADIUS

/**
 * The `{quadkey}` token: the tile's Bing-style quadkey, one base-4 digit per zoom level.
 *
 * ```
 * function getQuadkey(z, x, y) {
 *     let quadkey = '';
 *     for (let i = z; i > 0; i--) {
 *         const mask = 1 << (i - 1);
 *         quadkey += ((x & mask ? 1 : 0) + (y & mask ? 2 : 0));
 *     }
 *     return quadkey;
 * }
 * ```
 */
internal fun tileQuadkey(z: Int, x: Int, y: Int): String {
    val quadkey = StringBuilder(z.coerceAtLeast(0))
    for (i in z downTo 1) {
        val mask = 1 shl (i - 1)
        val digit = (if (x and mask != 0) 1 else 0) + (if (y and mask != 0) 2 else 0)
        quadkey.append(digit)
    }
    return quadkey.toString()
}

/**
 * The `{bbox-epsg-3857}` token used in WMS tile URLs: the tile's bounding box in EPSG:3857 metres
 * as `minX,minY,maxX,maxY`.
 *
 * ```
 * function getTileBBox(x, y, z) {
 *     // for Google/OSM tile scheme we need to alter the y
 *     y = Math.pow(2, z) - y - 1;
 *
 *     const min = getEpsg3857Coords(x * 256, y * 256, z);
 *     const max = getEpsg3857Coords((x + 1) * 256, (y + 1) * 256, z);
 *
 *     return `${min[0]},${min[1]},${max[0]},${max[1]}`;
 * }
 * ```
 *
 * The flip is unconditional: it turns the xyz row this port addresses tiles by into the
 * bottom-up row a projected bounding box needs, which is a different thing from TileJSON's
 * `scheme` and happens whatever that says.
 *
 * Every component is written by [plainDecimalString] rather than by `Double.toString`, because the
 * string has to be JavaScript's: mercator metres live around `1e7`, where Kotlin switches to
 * exponent notation on every target and `String(x)` in JS does not, and a WMS server is handed the
 * text verbatim.
 */
internal fun tileBBoxEpsg3857(z: Int, x: Int, y: Int): String {
    val flippedY = (1L shl z) - y - 1

    val (minX, minY) = epsg3857Coords(x.toDouble() * 256.0, flippedY.toDouble() * 256.0, z)
    val (maxX, maxY) = epsg3857Coords((x + 1).toDouble() * 256.0, (flippedY + 1).toDouble() * 256.0, z)

    return "${plainDecimalString(minX)},${plainDecimalString(minY)}," +
        "${plainDecimalString(maxX)},${plainDecimalString(maxY)}"
}

/**
 * ```
 * function getEpsg3857Coords(x, y, z) {
 *     const resolution = (2 * EPSG3857_HALF_CIRCUMFERENCE / 256) / Math.pow(2, z);
 *     const mercX = x * resolution - EPSG3857_HALF_CIRCUMFERENCE;
 *     const mercY = y * resolution - EPSG3857_HALF_CIRCUMFERENCE;
 *
 *     return [mercX, mercY];
 * }
 * ```
 */
private fun epsg3857Coords(x: Double, y: Double, z: Int): Pair<Double, Double> {
    val resolution = (2.0 * EPSG3857_HALF_CIRCUMFERENCE / 256.0) / (1L shl z).toDouble()
    return (x * resolution - EPSG3857_HALF_CIRCUMFERENCE) to
        (y * resolution - EPSG3857_HALF_CIRCUMFERENCE)
}

/**
 * The `{prefix}` token: `(this.x % 16).toString(16) + (this.y % 16).toString(16)`, the two hex
 * digits a sharded tile server keys its directories by.
 */
internal fun tilePrefix(x: Int, y: Int): String {
    val hex = "0123456789abcdef"
    return "${hex[x and 0xF]}${hex[y and 0xF]}"
}
