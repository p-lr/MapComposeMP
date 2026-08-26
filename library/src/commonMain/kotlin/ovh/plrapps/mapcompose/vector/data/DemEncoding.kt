package ovh.plrapps.mapcompose.vector.data

import ovh.plrapps.mapcompose.vector.spec.style.Source

/**
 * How a `raster-dem` source's tiles pack elevation into RGB.
 *
 * Mirrors `DEMEncoding` in `maplibre-gl/src/data/dem_data.ts`. [MAPBOX] is the style-spec default.
 */
enum class DemEncoding {
    MAPBOX,
    TERRARIUM,
    CUSTOM;

    companion object {
        fun fromSpec(encoding: String?): DemEncoding = when (encoding?.lowercase()) {
            null, "", "mapbox" -> MAPBOX
            "terrarium" -> TERRARIUM
            "custom" -> CUSTOM
            else -> MAPBOX
        }
    }
}

/**
 * The per-channel factors that turn one DEM pixel into metres.
 *
 * Upstream feeds these to the prepare shader as one `vec4` and evaluates
 * `dot(vec4(r, g, b, -1.0), u_unpack)`, which is exactly [elevationOf]. The three named encodings'
 * factors are `DEMData`'s; `custom` takes them from the source itself.
 */
data class DemUnpack(
    val red: Double,
    val green: Double,
    val blue: Double,
    val baseShift: Double,
) {
    /** Metres above sea level, from one pixel's 0..255 channels. */
    fun elevationOf(r: Int, g: Int, b: Int): Double =
        r * red + g * green + b * blue - baseShift

    companion object {
        val MAPBOX = DemUnpack(red = 6553.6, green = 25.6, blue = 0.1, baseShift = 10000.0)
        val TERRARIUM = DemUnpack(red = 256.0, green = 1.0, blue = 1.0 / 256.0, baseShift = 32768.0)

        /**
         * The unpack vector a style source declares.
         *
         * `custom` reads `redFactor` / `greenFactor` / `blueFactor` / `baseShift` off the source,
         * each falling back to the style-spec default (`1.0`, `1.0`, `1.0`, `0.0`); the other two
         * encodings ignore them, as upstream does.
         */
        fun of(source: Source): DemUnpack = when (DemEncoding.fromSpec(source.encoding)) {
            DemEncoding.MAPBOX -> MAPBOX
            DemEncoding.TERRARIUM -> TERRARIUM
            DemEncoding.CUSTOM -> DemUnpack(
                red = source.redFactor ?: 1.0,
                green = source.greenFactor ?: 1.0,
                blue = source.blueFactor ?: 1.0,
                baseShift = source.baseShift ?: 0.0,
            )
        }
    }
}
