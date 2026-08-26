package ovh.plrapps.mapcompose.vector.data

/**
 * The `type` of a style source.
 *
 * MapLibre keys a source's whole loading strategy off this -- `vector` sources are protobuf tiles,
 * `raster` sources are ordinary images, `raster-dem` is an image whose channels encode elevation.
 * It used to be parsed and dropped here, so every source was fetched and pbf-decoded as MVT; that
 * is what blocked the `raster` layer.
 *
 * [VECTOR], [RASTER] and [RASTER_DEM] change what the pipeline does. The rest are named so that a
 * style declaring one is recognised rather than silently treated as a vector source.
 */
enum class SourceType {
    VECTOR,
    RASTER,
    RASTER_DEM,
    GEOJSON,
    IMAGE,
    VIDEO,
    UNKNOWN;

    companion object {
        /**
         * Maps the style-spec `type` string onto this enum.
         *
         * An absent type means [VECTOR]: it is what every source in this codebase's styles was
         * treated as before types were modelled, so defaulting to it keeps those styles rendering.
         * An unrecognised one is [UNKNOWN] rather than [VECTOR], because feeding an unknown format
         * to the protobuf decoder only produces noise.
         */
        fun fromSpec(type: String?): SourceType = when (type?.lowercase()) {
            null, "", "vector" -> VECTOR
            "raster" -> RASTER
            "raster-dem" -> RASTER_DEM
            "geojson" -> GEOJSON
            "image" -> IMAGE
            "video" -> VIDEO
            else -> UNKNOWN
        }
    }
}
