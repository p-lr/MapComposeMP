package ovh.plrapps.mapcompose.vector.data

/**
 * The wire format of a `vector` source's tiles, the style spec's `source_vector.encoding`.
 *
 * ```json
 * "encoding": {
 *   "type": "enum",
 *   "values": { "mvt": {...}, "mlt": {...} },
 *   "default": "mvt"
 * }
 * ```
 *
 * Upstream picks its decoder off it in `src/source/vector_tile_worker_source.ts`:
 *
 * ```ts
 * const vectorTile = params.encoding !== 'mlt'
 *     ? new VectorTile(new PbfReader(rawData))
 *     : new MLTVectorTile(rawData);
 * ```
 *
 * [MLT] -- MapLibre Tiles -- is **not decoded here**, and a source declaring it is refused at load
 * by `getMapLibreConfiguration` rather than fetched: pbandk does not reliably throw on foreign
 * bytes, it collects them into `Tile.unknownFields`, so feeding an MLT tile to
 * `VectorRasterizer.decodePBFFromByteArray` yields an empty or garbage tile and no error at all.
 * That is the same reasoning as [SourceType.UNKNOWN]'s.
 *
 * Porting a decoder is a project of its own and deliberately not attempted: upstream delegates to
 * the npm package `@maplibre/mlt`, the only implementations that exist are TypeScript, Java, Rust
 * and C++ -- none reachable from `commonMain` across Android, iOS, desktop and wasm -- and the
 * format (column-oriented, FSST string dictionaries, FastPFOR/varint integer encodings,
 * morton-ordered geometry, its own protobuf tileset-metadata schema) is still experimental and
 * evolving. This enum is the seam such a decoder would plug into.
 */
enum class VectorEncoding {
    /** Mapbox Vector Tiles, the spec default and the only format this port decodes. */
    MVT,

    /** MapLibre Tiles. Recognised so that a source declaring it can be refused, never decoded. */
    MLT;

    companion object {
        /**
         * Maps the style-spec `encoding` string onto this enum.
         *
         * Upstream's test is `params.encoding !== 'mlt'`, so an absent or unrecognised value is
         * [MVT] rather than an error -- a `raster-dem` source's `encoding` shares the same JSON key
         * and must not be mistaken for a vector one.
         */
        fun fromSpec(encoding: String?): VectorEncoding =
            if (encoding?.lowercase() == "mlt") MLT else MVT
    }
}
