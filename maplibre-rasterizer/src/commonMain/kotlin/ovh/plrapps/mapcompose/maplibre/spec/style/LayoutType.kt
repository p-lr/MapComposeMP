package ovh.plrapps.mapcompose.maplibre.spec.style

enum class LayoutType {
    FILL,
    LINE,
    SYMBOL,
    CIRCLE,
    HEATMAP,
    RASTER,
    HILLSHADE,
    FILL_EXTRUSION,
    UNDEFINED,
    BACKGROUND;

    companion object {
        fun parse(type: String): LayoutType {
            val normalized = type.uppercase().replace('-', '_').trim()
            return LayoutType.entries.find { it.name == normalized } ?: UNDEFINED
        }
    }
} 