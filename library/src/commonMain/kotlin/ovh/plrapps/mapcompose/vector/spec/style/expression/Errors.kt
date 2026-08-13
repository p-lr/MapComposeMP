package ovh.plrapps.mapcompose.vector.spec.style.expression

/**
 * A parse-time error. Ported from `maplibre-style-spec/src/expression/parsing_error.ts`.
 *
 * These are *collected* into [ParsingContext.errors] rather than thrown, so one bad expression
 * never aborts parsing of the surrounding style.
 */
data class ExpressionParsingError(val key: String, override val message: String) : Exception(message)

/**
 * An evaluation-time error. Ported from `maplibre-style-spec/src/expression/runtime_error.ts`.
 *
 * [path] is the index path of the throwing sub-expression (e.g. `[3][0]`); the empty string means
 * the throw is at the root of the expression.
 */
class RuntimeError(override val message: String, val path: String = "") : Exception(message)
