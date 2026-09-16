package ovh.plrapps.mapcompose.vector.data.geojson

import ovh.plrapps.mapcompose.vector.spec.Tile
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * Cuts projected GeoJSON features into MVT-shaped tiles.
 *
 * A `geojson` source has no tile server: MapLibre runs `geojson-vt` over the document and serves the
 * result from memory. This is the same idea, reduced to what the painters need -- transform into
 * tile-local coordinates, clip to the tile with a buffer, simplify, and emit a [Tile] the rest of
 * the pipeline cannot tell from a decoded protobuf one.
 *
 * **Divergences from `geojson-vt`.** It builds a pyramid of tiles up front and splits each parent
 * into four children, reusing the parent's already-clipped geometry; this cuts every requested tile
 * straight from the whole document, which is simpler and costs `O(features)` per tile rather than
 * `O(features)` once. It also precomputes each vertex's simplification distance once for all zooms,
 * where this runs Douglas-Peucker per tile at that tile's own tolerance -- the same shape of
 * result, recomputed. Geometry is wrapped across the antimeridian as `wrap.ts` wraps it, though per
 * tile rather than once up front -- see [WORLD_OFFSETS]. Neither `cluster` nor `lineMetrics` is
 * supported.
 *
 * [buffer] and [tolerance] are in tile units at [extent]; the source's own options are in style
 * pixels and are converted by [pixelsToTileUnits], which is upstream's `_pixelsToTileUnits`.
 * [maxZoom] exists only for the tolerance: `geojson-vt` simplifies with tolerance 0 at the source's
 * `maxzoom` (`splitTile`'s `z === options.maxZoom ? 0 : …`), so the deepest level a source serves
 * keeps every vertex.
 */
class GeoJsonTiler(
    private val features: List<GeoJsonFeature>,
    private val extent: Int = DEFAULT_EXTENT,
    private val buffer: Double = DEFAULT_BUFFER,
    private val tolerance: Double = DEFAULT_TOLERANCE,
    private val maxZoom: Int = GeoJsonSource.DEFAULT_MAX_ZOOM,
) {

    /**
     * Every feature with the normalized x range it spans, so [tile] can tell at a glance which world
     * copies of it can reach the tile being cut. See [WORLD_OFFSETS].
     */
    private val spans: List<FeatureSpan> = features.map { feature ->
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        for (ring in feature.rings) {
            for (point in ring) {
                if (point.x < minX) minX = point.x
                if (point.x > maxX) maxX = point.x
            }
        }
        FeatureSpan(feature, minX, maxX)
    }

    /** The tile at `(z, x, y)`, or `null` when no feature reaches it. */
    fun tile(z: Int, x: Int, y: Int): Tile? {
        if (features.isEmpty()) return null
        /* `splitTile`: the deepest level a source serves is not simplified at all, because nothing
         * below it will ever add the detail back. Every other level simplifies at the flat
         * tile-unit tolerance upstream's `options.tolerance / ((1 << z) * options.extent)` works
         * out to once it is measured against that level's own tile. */
        val tolerance = if (z >= maxZoom) 0.0 else tolerance
        val scale = 2.0.pow(z)
        val originX = x / scale
        val originY = y / scale

        val builder = TileBuilder(extent)
        for (span in spans) {
            val feature = span.feature
            for (offset in WORLD_OFFSETS) {
                /* Whether this world copy reaches the tile at all, in tile units, before any
                 * coordinate is transformed: a document that stays inside `[0, 1]` -- which is
                 * every document that does not cross the antimeridian -- pays two comparisons per
                 * feature for the two copies it does not have. Touching the clip boundary is not
                 * reaching the tile: a copy admitted on equality survives the clip as a degenerate
                 * two-vertex line sitting on the boundary. */
                val low = (span.minX + offset - originX) * scale * extent
                val high = (span.maxX + offset - originX) * scale * extent
                if (high <= -buffer || low >= extent + buffer) continue

                val local = feature.rings.map { ring ->
                    ring.map { point ->
                        Vertex(
                            ((point.x + offset - originX) * scale * extent),
                            ((point.y - originY) * scale * extent),
                        )
                    }
                }
                val clipped = clip(feature.type, local)
                if (clipped.isEmpty()) continue
                val simplified = if (feature.type == Tile.GeomType.POINT) {
                    clipped
                } else {
                    clipped.map { simplify(it, tolerance) }
                        .filter { it.size >= minimumVertices(feature.type) }
                }
                if (simplified.isEmpty()) continue
                builder.add(feature, simplified)
            }
        }
        return builder.build()
    }

    /** A feature and the normalized x range it spans; see [spans]. */
    private class FeatureSpan(val feature: GeoJsonFeature, val minX: Double, val maxX: Double)

    private fun minimumVertices(type: Tile.GeomType): Int =
        if (type == Tile.GeomType.POLYGON) 3 else 2

    // region clipping

    private fun clip(type: Tile.GeomType, rings: List<List<Vertex>>): List<List<Vertex>> {
        val min = -buffer
        val max = extent + buffer
        return when (type) {
            Tile.GeomType.POINT -> rings.map { ring ->
                ring.filter { it.x >= min && it.x <= max && it.y >= min && it.y <= max }
            }.filter { it.isNotEmpty() }

            Tile.GeomType.POLYGON -> rings.mapNotNull { ring ->
                clipPolygonRing(ring, min, max).takeIf { it.size >= 3 }
            }

            else -> rings.flatMap { clipLine(it, min, max) }
        }
    }

    /**
     * Sutherland-Hodgman against the tile's four edges.
     *
     * A polygon must stay closed, so it is clipped as an area rather than cut into pieces: the
     * algorithm walks each edge in turn and inserts the crossing points, which leaves the ring
     * running along the tile's border where it used to leave the tile.
     */
    private fun clipPolygonRing(ring: List<Vertex>, min: Double, max: Double): List<Vertex> {
        var current = ring
        for (edge in 0 until 4) {
            if (current.isEmpty()) return emptyList()
            val next = mutableListOf<Vertex>()
            for (i in current.indices) {
                val from = current[i]
                val to = current[(i + 1) % current.size]
                val fromIn = inside(from, edge, min, max)
                val toIn = inside(to, edge, min, max)
                if (fromIn) next += from
                if (fromIn != toIn) next += intersect(from, to, edge, min, max)
            }
            current = next
        }
        return current
    }

    /**
     * Cuts a line into the pieces that fall inside the tile.
     *
     * Unlike a polygon a line may legitimately become several lines, so each crossing starts or
     * ends a piece rather than being absorbed into the border.
     */
    private fun clipLine(line: List<Vertex>, min: Double, max: Double): List<List<Vertex>> {
        val out = mutableListOf<List<Vertex>>()
        var current = mutableListOf<Vertex>()
        for (i in 0 until line.size - 1) {
            val from = line[i]
            val to = line[i + 1]
            val segment = clipSegment(from, to, min, max)
            if (segment == null) {
                if (current.size >= 2) out += current
                current = mutableListOf()
                continue
            }
            val (start, end) = segment
            if (current.isEmpty()) {
                current.add(start)
            } else if (current.last().notAt(start)) {
                // The line left the tile and came back: that is two pieces, not one.
                if (current.size >= 2) out += current
                current = mutableListOf(start)
            }
            current.add(end)
        }
        if (current.size >= 2) out += current
        return out
    }

    /** Liang-Barsky: the part of the segment inside the box, or `null` when none of it is. */
    private fun clipSegment(from: Vertex, to: Vertex, min: Double, max: Double): Pair<Vertex, Vertex>? {
        var t0 = 0.0
        var t1 = 1.0
        val dx = to.x - from.x
        val dy = to.y - from.y

        for (edge in 0 until 4) {
            val p: Double
            val q: Double
            when (edge) {
                0 -> { p = -dx; q = from.x - min }
                1 -> { p = dx; q = max - from.x }
                2 -> { p = -dy; q = from.y - min }
                else -> { p = dy; q = max - from.y }
            }
            if (p == 0.0) {
                if (q < 0.0) return null
                continue
            }
            val r = q / p
            if (p < 0.0) {
                if (r > t1) return null
                if (r > t0) t0 = r
            } else {
                if (r < t0) return null
                if (r < t1) t1 = r
            }
        }
        return Vertex(from.x + t0 * dx, from.y + t0 * dy) to Vertex(from.x + t1 * dx, from.y + t1 * dy)
    }

    private fun inside(vertex: Vertex, edge: Int, min: Double, max: Double): Boolean = when (edge) {
        0 -> vertex.x >= min
        1 -> vertex.x <= max
        2 -> vertex.y >= min
        else -> vertex.y <= max
    }

    private fun intersect(from: Vertex, to: Vertex, edge: Int, min: Double, max: Double): Vertex =
        when (edge) {
            0 -> Vertex(min, lerp(from.y, to.y, (min - from.x) / (to.x - from.x)))
            1 -> Vertex(max, lerp(from.y, to.y, (max - from.x) / (to.x - from.x)))
            2 -> Vertex(lerp(from.x, to.x, (min - from.y) / (to.y - from.y)), min)
            else -> Vertex(lerp(from.x, to.x, (max - from.y) / (to.y - from.y)), max)
        }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    // endregion

    // region simplification

    /**
     * Douglas-Peucker.
     *
     * Ends are always kept, so a ring stays closed and a line keeps its extent; only interior
     * vertices nearer than [tolerance] to the chord they sit on are dropped.
     */
    internal fun simplify(points: List<Vertex>, tolerance: Double): List<Vertex> {
        if (points.size <= 2 || tolerance <= 0.0) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        simplifySection(points, 0, points.size - 1, tolerance * tolerance, keep)
        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun simplifySection(
        points: List<Vertex>,
        first: Int,
        last: Int,
        squaredTolerance: Double,
        keep: BooleanArray,
    ) {
        if (last <= first + 1) return
        var maxSquared = 0.0
        var index = first
        for (i in first + 1 until last) {
            val squared = squaredDistanceToSegment(points[i], points[first], points[last])
            if (squared > maxSquared) {
                maxSquared = squared
                index = i
            }
        }
        if (maxSquared <= squaredTolerance) return
        keep[index] = true
        simplifySection(points, first, index, squaredTolerance, keep)
        simplifySection(points, index, last, squaredTolerance, keep)
    }

    private fun squaredDistanceToSegment(point: Vertex, from: Vertex, to: Vertex): Double {
        var x = from.x
        var y = from.y
        var dx = to.x - x
        var dy = to.y - y
        if (dx != 0.0 || dy != 0.0) {
            val t = ((point.x - x) * dx + (point.y - y) * dy) / (dx * dx + dy * dy)
            if (t > 1.0) {
                x = to.x
                y = to.y
            } else if (t > 0.0) {
                x += dx * t
                y += dy * t
            }
        }
        dx = point.x - x
        dy = point.y - y
        return dx * dx + dy * dy
    }

    // endregion

    class Vertex(val x: Double, val y: Double) {
        fun notAt(other: Vertex): Boolean = abs(x - other.x) > 1e-9 || abs(y - other.y) > 1e-9
    }

    companion object {
        /** MVT's usual tile resolution, and what the painters assume when a layer omits it. */
        const val DEFAULT_EXTENT = 4096

        /**
         * The world copies a feature is cut from, in normalized x.
         *
         * `geojson-vt` wraps before it indexes (`src/wrap.ts`): it clips a left copy at
         * `[-1 - buffer, buffer]` and shifts it by `+1`, a right copy at `[1 - buffer, 2 + buffer]`
         * shifted by `-1`, and concatenates both around the centre copy. Without it a coordinate
         * past the antimeridian -- longitude 181 projects to `x ≈ 1.0028` -- falls in no tile at
         * all, and a line crossing it loses the half that ran over.
         *
         * This cuts every requested tile straight from the document rather than building a pyramid,
         * so the equivalent is per tile: offer each feature at each offset and let the clip decide.
         * A feature is cut twice where two copies genuinely reach one tile, which is what upstream's
         * concatenation does too.
         */
        private val WORLD_OFFSETS = doubleArrayOf(-1.0, 0.0, 1.0)

        /**
         * A style pixel in tile units, upstream's `GeoJSONSource._pixelsToTileUnits`:
         * `pixelValue * (EXTENT / this.tileSize)`, with `tileSize` hardcoded to 512 there.
         *
         * Both of a `geojson` source's geometry options are authored in style pixels and used in
         * tile units, and getting the conversion wrong is invisible -- it changes only how much
         * geometry survives the cut.
         */
        fun pixelsToTileUnits(pixels: Double, extent: Int): Double = pixels * (extent / 512.0)

        /** The spec's default `buffer`, in style pixels. */
        const val DEFAULT_BUFFER_PIXELS = 128.0

        /** The spec's default `tolerance`, in style pixels. */
        const val DEFAULT_TOLERANCE_PIXELS = 0.375

        /**
         * Geometry kept outside the tile so a stroke or a label is not cut at the edge.
         *
         * A quarter of a tile on each side, which is what upstream's 128 px default comes to. It
         * used to be a flat 64 units -- a sixteenth of that, 8 px on a 512 px tile -- and this port
         * needs the buffer *more* than upstream does, not less: a tile is rasterized into its own
         * bitmap and that bitmap is the clip, where upstream draws the whole viewport into one
         * framebuffer and can let a shape spill across a tile boundary.
         */
        val DEFAULT_BUFFER: Double = pixelsToTileUnits(DEFAULT_BUFFER_PIXELS, DEFAULT_EXTENT)

        /** Douglas-Peucker tolerance in tile units; `geojson-vt`'s default 0.375 px, i.e. 3. */
        val DEFAULT_TOLERANCE: Double = pixelsToTileUnits(DEFAULT_TOLERANCE_PIXELS, DEFAULT_EXTENT)

        /** The layer name a geojson tile carries. Upstream's `GeoJSONWrapper` uses the same one. */
        const val LAYER_NAME = "_geojsonTileLayer"

        /**
         * The tag key a feature's id rides under when the MVT wire format cannot carry it.
         *
         * `Tile.Feature.id` is a protobuf `uint64` and `spec/vector_tile.kt` is pbandk-generated,
         * so a string id -- which RFC 7946 allows and upstream keeps -- has nowhere else to go. A
         * geojson tile is synthesized by this tiler rather than fetched, so smuggling the id
         * through the layer's own tag table costs nothing and means it survives every path a tile
         * takes: the overzoom crop, the neighbour gather and both tile caches.
         *
         * Private to this tiler and to `renderer/BaseRenderer.buildEvalFeature`, which lifts it
         * back out and removes it, so it never reaches `["get"]` or `["properties"]`. The leading
         * NUL is what makes it a key no GeoJSON document writes.
         */
        const val SYNTHETIC_ID_KEY = "\u0000id"
    }

    /**
     * Assembles the MVT structures a [Tile] is made of.
     *
     * The painters read features through `Tile.Layer`'s shared key and value tables, so the
     * properties have to be interned into those rather than carried per feature.
     */
    private class TileBuilder(private val extent: Int) {
        private val keys = mutableListOf<String>()
        private val keyIndex = mutableMapOf<String, Int>()
        private val values = mutableListOf<Tile.Value>()

        /*
         * Keyed on the raw property value, not on the `Tile.Value` built from it: pbandk messages
         * carry an extension-field set that compares by identity, so two structurally identical
         * values would never be recognised as the same entry.
         */
        private val valueIndex = mutableMapOf<Any, Int>()
        private val features = mutableListOf<Tile.Feature>()

        fun add(feature: GeoJsonFeature, rings: List<List<Vertex>>) {
            val tags = mutableListOf<Int>()
            for ((key, raw) in feature.properties) {
                if (raw == null) continue
                val value = valueOf(raw) ?: continue
                tags += keyIndex.getOrPut(key) { keys.add(key); keys.size - 1 }
                tags += valueIndex.getOrPut(raw) { values.add(value); values.size - 1 }
            }
            /* The id goes through the tag table whatever its type -- see [SYNTHETIC_ID_KEY] -- and
             * additionally through `Tile.Feature.id` when it is an exact integer, so that the tile
             * stays self-describing for anything reading it as an ordinary MVT tile. */
            feature.id?.let { id ->
                valueOf(id)?.let { value ->
                    tags += keyIndex.getOrPut(SYNTHETIC_ID_KEY) { keys.add(SYNTHETIC_ID_KEY); keys.size - 1 }
                    tags += valueIndex.getOrPut(id) { values.add(value); values.size - 1 }
                }
            }
            features += Tile.Feature(
                id = integralId(feature.id),
                type = feature.type,
                geometry = encode(feature.type, rings),
                tags = tags,
            )
        }

        /** The id as `Tile.Feature.id` can hold it, or `null` when the wire format cannot. */
        private fun integralId(id: Any?): Long? = when (id) {
            is Long -> id
            is Double -> id.takeIf { it.isFinite() && it == floor(it) && abs(it) <= 9.007199254740992E15 }?.toLong()
            else -> null
        }

        fun build(): Tile? {
            if (features.isEmpty()) return null
            return Tile(
                layers = listOf(
                    Tile.Layer(
                        version = 2,
                        name = LAYER_NAME,
                        features = features,
                        keys = keys,
                        values = values,
                        extent = extent,
                    )
                )
            )
        }

        private fun valueOf(raw: Any?): Tile.Value? = when (raw) {
            null -> null
            is String -> Tile.Value(stringValue = raw)
            is Boolean -> Tile.Value(boolValue = raw)
            is Number -> Tile.Value(doubleValue = raw.toDouble())
            else -> Tile.Value(stringValue = raw.toString())
        }

        /** Encodes rings as an MVT command stream, the form every decoder here already reads. */
        private fun encode(type: Tile.GeomType, rings: List<List<Vertex>>): List<Int> {
            val out = mutableListOf<Int>()
            var cursorX = 0
            var cursorY = 0

            if (type == Tile.GeomType.POINT) {
                val points = rings.flatten()
                if (points.isEmpty()) return out
                out += command(MOVE_TO, points.size)
                for (point in points) {
                    val x = point.x.toIntRounded()
                    val y = point.y.toIntRounded()
                    out += zigZag(x - cursorX)
                    out += zigZag(y - cursorY)
                    cursorX = x
                    cursorY = y
                }
                return out
            }

            for (ring in rings) {
                if (ring.isEmpty()) continue
                val first = ring.first()
                val firstX = first.x.toIntRounded()
                val firstY = first.y.toIntRounded()
                out += command(MOVE_TO, 1)
                out += zigZag(firstX - cursorX)
                out += zigZag(firstY - cursorY)
                cursorX = firstX
                cursorY = firstY

                val rest = ring.drop(1)
                if (rest.isNotEmpty()) {
                    out += command(LINE_TO, rest.size)
                    for (point in rest) {
                        val x = point.x.toIntRounded()
                        val y = point.y.toIntRounded()
                        out += zigZag(x - cursorX)
                        out += zigZag(y - cursorY)
                        cursorX = x
                        cursorY = y
                    }
                }
                if (type == Tile.GeomType.POLYGON) out += command(CLOSE_PATH, 1)
            }
            return out
        }

        private fun Double.toIntRounded(): Int =
            (this + if (this < 0) -0.5 else 0.5).toInt()

        private fun command(id: Int, count: Int): Int = (count shl 3) or id

        private fun zigZag(n: Int): Int = (n shl 1) xor (n shr 31)

        private companion object {
            const val MOVE_TO = 1
            const val LINE_TO = 2
            const val CLOSE_PATH = 7
        }
    }
}
