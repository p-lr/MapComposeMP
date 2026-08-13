package ovh.plrapps.mapcompose.vector.spec.style.utils

import ovh.plrapps.mapcompose.vector.spec.style.expression.ExpressionParsingError

/** A problem found while parsing a style, recorded instead of thrown. */
data class StyleDiagnostic(
    /** Where the problem is: the property's JSON text, or the layer id for a filter. */
    val location: String,
    val key: String,
    val message: String,
) {
    override fun toString(): String = "$location$key: $message"
}

/**
 * Collects expression and filter parse errors during style deserialization.
 *
 * kotlinx-serialization gives a custom serializer no channel to report non-fatal problems, so
 * diagnostics are accumulated here and drained by `getMapLibreConfiguration` once the style has
 * been decoded. MapLibre's equivalent is the `Array<ExpressionParsingError>` that `createExpression`
 * returns to the style loader.
 *
 * Style parsing is single-threaded (one `Json.decodeFromString` call), so a plain list is enough.
 */
object StyleDiagnostics {
    private val entries = mutableListOf<StyleDiagnostic>()

    fun report(location: String, errors: List<ExpressionParsingError>) {
        for (error in errors) {
            entries.add(StyleDiagnostic(location = location, key = error.key, message = error.message))
        }
    }

    fun report(location: String, message: String) {
        entries.add(StyleDiagnostic(location = location, key = "", message = message))
    }

    /** Returns everything collected since the last call and clears the buffer. */
    fun drain(): List<StyleDiagnostic> {
        val result = entries.toList()
        entries.clear()
        return result
    }
}
