package ovh.plrapps.mapcompose.vector.symbol

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * Stable identities for symbols across tiles and zoom levels, ported from
 * `maplibre-gl-js/src/symbol/cross_tile_symbol_index.ts`.
 *
 * Upstream's own summary of why it exists:
 *
 * > The CrossTileSymbolIndex generally works on the assumption that a conceptual "unique symbol"
 * > can be identified by the text of the label combined with the anchor point. The goal is to assign
 * > these conceptual "unique symbols" a shared crossTileID that can be used by Placement to keep
 * > fading opacity states consistent and to deduplicate labels.
 *
 * Both halves matter here. **Deduplication** is what retired this port's own stand-in, a rule that
 * dropped a repeat of the same text within 250 viewport pixels: a road labelled once in each tile it
 * crosses is now recognised as one symbol, so the seam copy inherits the same id and is placed once.
 * **Consistency** is what makes fading and `text-variable-anchor` work at all -- both are keyed on
 * [SymbolInstance.crossTileID], so a label whose tile is replaced by its child on a zoom keeps its
 * opacity and its chosen anchor instead of starting over.
 *
 * Two upstream details are deliberately not ported:
 *
 * - The `KDBush` index that upstream builds once a key carries more than `KDBUSH_THRESHHOLD` (128)
 *   symbols. Its own comments say the two paths agree -- both claim "the lowest-index unclaimed
 *   candidate within one grid unit" -- so it is a query optimisation, not behaviour, and only the
 *   linear path is here.
 * - `handleWrapJump`, which re-keys the indexes when a user pans across the antimeridian and the
 *   longitude wraps. MapCompose's infinite scroll wraps the map's *x* rather than a longitude, and a
 *   tile reference here carries no wrap to rewrite.
 */
internal class CrossTileSymbolIndex {

    private val layerIndexes = mutableMapOf<String, CrossTileSymbolLayerIndex>()
    private val crossTileIDs = CrossTileIDs()
    private var maxBucketInstanceId = 0L

    /**
     * Assigns every instance in [buckets] a [SymbolInstance.crossTileID], and reports whether any
     * bucket was added or removed since the last call -- upstream's `addLayer`.
     */
    fun addLayer(layerId: String, buckets: List<SymbolBucket>, density: Float): Boolean {
        val layerIndex = layerIndexes.getOrPut(layerId) { CrossTileSymbolLayerIndex() }

        var symbolBucketsChanged = false
        val currentBucketIDs = mutableSetOf<Long>()

        for (bucket in buckets) {
            if (bucket.bucketInstanceId == 0L) {
                maxBucketInstanceId += 1
                bucket.bucketInstanceId = maxBucketInstanceId
            }
            if (layerIndex.addBucket(bucket, crossTileIDs, density)) symbolBucketsChanged = true
            currentBucketIDs += bucket.bucketInstanceId
        }

        if (layerIndex.removeStaleBuckets(currentBucketIDs)) symbolBucketsChanged = true

        return symbolBucketsChanged
    }

    /** Drops the indexes of layers the style no longer has -- upstream's `pruneUnusedLayers`. */
    fun pruneUnusedLayers(usedLayers: Set<String>) {
        layerIndexes.keys.retainAll(usedLayers)
    }

    /** Whether a layer is indexed at all, which is what `pruneUnusedLayers` is asserted through. */
    internal fun hasLayer(layerId: String): Boolean = layerId in layerIndexes

    /**
     * The ids claimed at one zoom, in ascending order.
     *
     * Upstream's `cross_tile_symbol_index.test.ts` reads `layerIndex.usedCrossTileIDs[zoom]`
     * directly; this is the same window, kept narrow.
     */
    internal fun usedCrossTileIDs(layerId: String, zoom: Int): List<Long> =
        layerIndexes[layerId]?.usedCrossTileIDsAt(zoom).orEmpty()
}

/** Upstream's `CrossTileIDs`: a monotonic counter, so 0 can mean "not yet assigned". */
private class CrossTileIDs {
    private var maxCrossTileID = 0L
    fun generate(): Long = ++maxCrossTileID
}

/** One style layer's indexes, by zoom then by tile -- upstream's `CrossTileSymbolLayerIndex`. */
private class CrossTileSymbolLayerIndex {

    private val indexes = mutableMapOf<Int, MutableMap<String, TileLayerIndex>>()
    private val usedCrossTileIDs = mutableMapOf<Int, MutableSet<Long>>()

    fun usedCrossTileIDsAt(zoom: Int): List<Long> = usedCrossTileIDs[zoom].orEmpty().sorted()

    fun addBucket(bucket: SymbolBucket, crossTileIDs: CrossTileIDs, density: Float): Boolean {
        /* Upstream keys `indexes` by `overscaledZ` and `usedCrossTileIDs` by the same. The two are
         * one number there because a tile id carries both; here `ref.z` names the tile actually
         * fetched and `bucket.zoom` the zoom it is shown at, which differ on an overzoomed source.
         * The parent/child walk below is canonical geometry, so it keys on `ref.z`; the "no two
         * symbols at one zoom claim the same parent" rule is about what is on screen, so that keys
         * on `bucket.zoom`. */
        val canonicalZ = bucket.ref.z
        val tileKey = bucket.tileKey
        val existing = indexes[canonicalZ]?.get(tileKey)
        if (existing != null) {
            if (existing.bucketInstanceId == bucket.bucketInstanceId) return false
            /* Replacing this bucket with an updated version: release the old one's claimed ids now,
             * so the new bucket can take them. The index entry itself survives until
             * `removeStaleBuckets`.
             *
             * The ids are released at the zoom the *existing* index claimed them under, which is not
             * the new bucket's when an overzoomed source is being redrawn at another display zoom --
             * the canonical tile, and so the index slot, is the same for both. Releasing at the new
             * bucket's zoom left them claimed under the old one, and on the way back down the map
             * every label failed to match and was handed a fresh identity: a fade out and in on
             * every zoom step. */
            removeBucketCrossTileIDs(existing.bucketZoom, existing)
        }

        for (instance in bucket.instances) instance.crossTileID = 0L

        val zoomCrossTileIDs = usedCrossTileIDs.getOrPut(bucket.zoom) { mutableSetOf() }

        for ((indexZoom, zoomIndexes) in indexes) {
            if (indexZoom > canonicalZ) {
                for (childIndex in zoomIndexes.values) {
                    if (childIndex.isChildOf(bucket)) {
                        childIndex.findMatches(bucket, zoomCrossTileIDs, density)
                    }
                }
            } else {
                zoomIndexes[parentTileKey(bucket, indexZoom)]
                    ?.findMatches(bucket, zoomCrossTileIDs, density)
            }
        }

        for (instance in bucket.instances) {
            if (instance.crossTileID == 0L) {
                // symbol did not match any known symbol, assign a new id
                instance.crossTileID = crossTileIDs.generate()
                zoomCrossTileIDs += instance.crossTileID
            }
        }

        indexes.getOrPut(canonicalZ) { mutableMapOf() }[tileKey] = TileLayerIndex(bucket, density)
        return true
    }

    private fun removeBucketCrossTileIDs(zoom: Int, removed: TileLayerIndex) {
        usedCrossTileIDs[zoom]?.removeAll(removed.crossTileIDs().toSet())
    }

    fun removeStaleBuckets(currentIDs: Set<Long>): Boolean {
        var tilesChanged = false
        for (zoomIndexes in indexes.values) {
            val stale = zoomIndexes.filterValues { it.bucketInstanceId !in currentIDs }
            for ((tileKey, index) in stale) {
                removeBucketCrossTileIDs(index.bucketZoom, index)
                zoomIndexes.remove(tileKey)
                tilesChanged = true
            }
        }
        return tilesChanged
    }
}

/**
 * One bucket's symbols, grouped by key and rounded onto a coarse grid -- upstream's
 * `TileLayerIndex`.
 */
private class TileLayerIndex(bucket: SymbolBucket, density: Float) {

    val bucketInstanceId: Long = bucket.bucketInstanceId

    /** The zoom the bucket was shown at, which is the key its claimed ids are held under. */
    val bucketZoom: Int = bucket.zoom

    private val canonicalZ = bucket.ref.z
    private val canonicalX = bucket.ref.x
    private val canonicalY = bucket.ref.y

    private class Entry(
        val positions: MutableList<Pair<Int, Int>> = mutableListOf(),
        val crossTileIDs: MutableList<Long> = mutableListOf(),
    )

    private val symbolsByKey: Map<String, Entry> = buildMap {
        val units = tileUnitsPerLayoutPixel(density, bucket.ref.span)
        for (instance in bucket.instances) {
            val entry = getOrPut(instance.key) { Entry() }
            entry.positions += Pair(
                floor(instance.tileAnchor.x * units * ROUNDING_FACTOR).toInt(),
                floor(instance.tileAnchor.y * units * ROUNDING_FACTOR).toInt(),
            )
            entry.crossTileIDs += instance.crossTileID
        }
    }

    fun crossTileIDs(): List<Long> = symbolsByKey.values.flatMap { it.crossTileIDs }

    fun isChildOf(bucket: SymbolBucket): Boolean {
        if (canonicalZ <= bucket.ref.z) return false
        val shift = canonicalZ - bucket.ref.z
        return (canonicalX shr shift) == bucket.ref.x && (canonicalY shr shift) == bucket.ref.y
    }

    /**
     * The other bucket's anchor in *this* index's frame, ported from `getScaledCoordinates`.
     *
     * Upstream's comment: the coordinates are made local to this index's tile, converted to its
     * zoom's scale, and down-sampled by the rounding factor "in order to be more tolerant of small
     * differences between tiles".
     */
    private fun scaledCoordinates(
        anchorX: Float,
        anchorY: Float,
        other: SymbolBucket,
        density: Float,
    ): Pair<Int, Int> {
        val units = tileUnitsPerLayoutPixel(density, other.ref.span)
        val scale = ROUNDING_FACTOR / 2.0.pow(other.ref.z - canonicalZ)
        val xWorld = (other.ref.x * TILE_UNITS + anchorX * units) * scale
        val yWorld = (other.ref.y * TILE_UNITS + anchorY * units) * scale
        val xOffset = canonicalX * TILE_UNITS * ROUNDING_FACTOR
        val yOffset = canonicalY * TILE_UNITS * ROUNDING_FACTOR
        return Pair(floor(xWorld - xOffset).toInt(), floor(yWorld - yOffset).toInt())
    }

    /**
     * Claims an id for every instance of [bucket] that matches one already indexed here, ported
     * from `findMatches` and `findMatchesForNonIndexedEntry`.
     *
     * Upstream's rules, both load-bearing: a match is any symbol with the same key within one grid
     * unit, and once a symbol has been matched against a parent symbol no other symbol at the same
     * zoom may claim that same parent.
     */
    fun findMatches(bucket: SymbolBucket, zoomCrossTileIDs: MutableSet<Long>, density: Float) {
        val tolerance = if (canonicalZ < bucket.ref.z) 1.0 else 2.0.pow(canonicalZ - bucket.ref.z)

        for (instance in bucket.instances) {
            if (instance.crossTileID != 0L) continue
            val entry = symbolsByKey[instance.key] ?: continue

            val (sx, sy) = scaledCoordinates(
                instance.tileAnchor.x, instance.tileAnchor.y, bucket, density,
            )

            for (i in entry.positions.indices) {
                val (px, py) = entry.positions[i]
                val crossTileID = entry.crossTileIDs[i]
                if (abs(px - sx) <= tolerance && abs(py - sy) <= tolerance &&
                    crossTileID != 0L && crossTileID !in zoomCrossTileIDs
                ) {
                    zoomCrossTileIDs += crossTileID
                    instance.crossTileID = crossTileID
                    break
                }
            }
        }
    }

    companion object {
        /**
         * One tile's width in the units anchors are compared in.
         *
         * Upstream compares in MVT `EXTENT` units; this port lays symbols out in style pixels
         * against [LAYOUT_TILE_SIZE], so a `tileAnchor` divided by the display density is already in
         * those units and the tile is [LAYOUT_TILE_SIZE] of them wide.
         */
        const val TILE_UNITS: Double = LAYOUT_TILE_SIZE.toDouble()

        /** Upstream's `roundingFactor`: "Round anchor positions to roughly 4 pixel grid". */
        const val ROUNDING_FACTOR: Double = 512.0 / TILE_UNITS / 2.0

        /**
         * What one layout pixel is worth in [TILE_UNITS], which is what anchors are compared in.
         *
         * A bucket's layout space is `LAYOUT_TILE_SIZE * density * span` wide and always covers
         * exactly one canonical tile, so both factors have to come out. The `span` is the one that
         * matters here: an overzoomed source keeps the same canonical tile as the map zooms and only
         * its span grows, so without dividing by it the same label sits at twice the coordinate one
         * zoom level up and matches nothing.
         */
        fun tileUnitsPerLayoutPixel(density: Float, span: Int): Double =
            1.0 / (density.coerceAtLeast(0.01f) * span.coerceAtLeast(1))
    }
}

/** The key a bucket is indexed under within one zoom: its canonical tile. */
private val SymbolBucket.tileKey: String get() = "${ref.z}/${ref.x}/${ref.y}"

/**
 * The key the ancestor of [bucket] at canonical [zoom] is indexed under -- upstream's `scaledTo`.
 */
private fun parentTileKey(bucket: SymbolBucket, zoom: Int): String {
    if (zoom >= bucket.ref.z) return bucket.tileKey
    val shift = bucket.ref.z - zoom
    return "$zoom/${bucket.ref.x shr shift}/${bucket.ref.y shr shift}"
}
