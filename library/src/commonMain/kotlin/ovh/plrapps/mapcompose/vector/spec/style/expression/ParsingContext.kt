package ovh.plrapps.mapcompose.vector.spec.style.expression

import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.Assertion
import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.Coercion
import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.Literal

/**
 * State associated with parsing at a given point in an expression tree.
 *
 * Ported from `maplibre-style-spec/src/expression/parsing_context.ts`.
 *
 * Two behaviours here carry most of the engine's weight:
 *
 * 1. **Automatic type annotation.** When the expected type is a concrete one and the parsed
 *    sub-expression is of the open `value` type, the result is wrapped in an [Assertion] (or a
 *    [Coercion] for color/formatted/image). This is what makes `["interpolate", …, ["get","x"], …]`
 *    work: `get` returns `value`, and the assertion narrows it to `number` at evaluation time.
 * 2. **Constant folding.** A node whose arguments are all literals is evaluated immediately and
 *    replaced with a [Literal].
 *
 * Errors are pushed into [errors] rather than thrown, so a single bad expression never aborts
 * parsing of the surrounding style.
 */
class ParsingContext(
    val registry: ExpressionRegistry,
    private val isConstantFunc: (Expression) -> Boolean,
    val path: List<Int> = emptyList(),
    /**
     * The expected type of this expression. Provided only to allow expression implementations to
     * infer argument types: a parser need not check that its output type matches [expectedType].
     */
    val expectedType: ExprType? = null,
    val scope: Scope = Scope(),
    val errors: MutableList<ExpressionParsingError> = mutableListOf(),
) {
    val key: String = path.joinToString(separator = "") { "[$it]" }

    enum class TypeAnnotation { ASSERT, COERCE, OMIT }

    fun parse(
        expr: Any?,
        index: Int? = null,
        expectedType: ExprType? = null,
        bindings: List<Pair<String, Expression>>? = null,
        typeAnnotation: TypeAnnotation? = null,
    ): Expression? {
        return if (index != null && index != 0) {
            concat(index, expectedType, bindings).parseInternal(expr, typeAnnotation)
        } else {
            parseInternal(expr, typeAnnotation)
        }
    }

    private fun parseInternal(exprIn: Any?, typeAnnotation: TypeAnnotation?): Expression? {
        var expr = exprIn
        if (expr == null || expr is String || expr is Boolean || expr is Number) {
            expr = listOf("literal", expr)
        }

        if (expr is List<*>) {
            if (expr.isEmpty()) {
                return error(
                    "Expected an array with at least one element. If you wanted a literal array, " +
                            "use [\"literal\", []]."
                )
            }

            val op = expr[0]
            if (op !is String) {
                error(
                    "Expression name must be a string, but found ${jsTypeName(op)} instead. " +
                            "If you wanted a literal array, use [\"literal\", [...]].",
                    0,
                )
                return null
            }

            val parser = registry[op]
                ?: return error(
                    "Unknown expression \"$op\". If you wanted a literal array, use [\"literal\", [...]].",
                    0,
                )

            var parsed = parser(expr, this) ?: return null

            val expected = expectedType
            if (expected != null) {
                val actual = parsed.type
                // When we expect a number, string, boolean, object or array but have a value, wrap
                // it in an assertion. When we expect a color, formatted string or image but have a
                // string or value, wrap it in a coercion. Otherwise, static type checking.
                if ((expected == StringType || expected == NumberType || expected == BooleanType ||
                            expected == ObjectType || expected is ArrayType) && actual == ValueType
                ) {
                    parsed = annotate(parsed, expected, typeAnnotation ?: TypeAnnotation.ASSERT)
                } else if ((expected == ColorType || expected == FormattedType || expected == ResolvedImageType) &&
                    (actual == ValueType || actual == StringType)
                ) {
                    parsed = annotate(parsed, expected, typeAnnotation ?: TypeAnnotation.COERCE)
                } else if (checkSubtype(expected, actual) != null) {
                    return null
                }
            }

            // If an expression's arguments are all literals, we can evaluate it immediately and
            // replace it with a literal value in the parsed result. Expressions that expect an
            // image should not be resolved here so we can later get the available images.
            if (parsed !is Literal && parsed.type != ResolvedImageType && isConstantFunc(parsed)) {
                val ec = EvaluationContext()
                try {
                    parsed = Literal(parsed.type, parsed.evaluate(ec))
                } catch (e: Exception) {
                    error(e.message ?: "Evaluation error")
                    return null
                }
            }

            return parsed
        }

        if (expr is Map<*, *>) {
            return error("Bare objects invalid. Use [\"literal\", {...}] instead.")
        }

        return error("Expected an array, but found ${jsTypeName(expr)} instead.")
    }

    private fun annotate(parsed: Expression, type: ExprType, typeAnnotation: TypeAnnotation): Expression =
        when (typeAnnotation) {
            TypeAnnotation.ASSERT -> Assertion(type, listOf(parsed), key)
            TypeAnnotation.COERCE -> Coercion(type, listOf(parsed), key)
            TypeAnnotation.OMIT -> parsed
        }

    /**
     * Returns a copy of this context suitable for parsing the sub-expression at [index], optionally
     * appending to the `let` binding map.
     *
     * Note that [errors] is shared by reference rather than cloned, exactly as upstream.
     */
    fun concat(
        index: Int?,
        expectedType: ExprType? = null,
        bindings: List<Pair<String, Expression>>? = null,
    ): ParsingContext {
        val newPath = if (index != null) path + index else path
        val newScope = if (bindings != null) scope.concat(bindings) else scope
        return ParsingContext(registry, isConstantFunc, newPath, expectedType, newScope, errors)
    }

    /** Pushes a parsing (or type-checking) error and returns `null` for convenient early return. */
    fun error(message: String, vararg keys: Int): Expression? {
        val k = "$key${keys.joinToString(separator = "") { "[$it]" }}"
        errors.add(ExpressionParsingError(k, message))
        return null
    }

    /** Returns `null` if [t] is a subtype of [expected]; otherwise records and returns the message. */
    fun checkSubtype(expected: ExprType, t: ExprType): String? {
        val err = ovh.plrapps.mapcompose.vector.spec.style.expression.checkSubtype(expected, t)
        if (err != null) error(err)
        return err
    }

    private companion object {
        fun jsTypeName(v: Any?): String = when (v) {
            null -> "object"
            is String -> "string"
            is Boolean -> "boolean"
            is Number -> "number"
            is List<*> -> "object"
            else -> "object"
        }
    }
}
