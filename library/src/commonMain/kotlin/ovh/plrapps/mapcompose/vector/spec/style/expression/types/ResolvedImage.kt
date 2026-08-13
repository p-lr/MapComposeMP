package ovh.plrapps.mapcompose.vector.spec.style.expression.types

/** Ported from `maplibre-style-spec/src/expression/types/resolved_image.ts`. */
data class ResolvedImage(val name: String, val available: Boolean) {
    override fun toString(): String = name

    companion object {
        /** Treats empty values as "no image", like upstream. */
        fun fromString(name: String?): ResolvedImage? {
            if (name.isNullOrEmpty()) return null
            return ResolvedImage(name = name, available = false)
        }
    }
}
