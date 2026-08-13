package ovh.plrapps.mapcompose.vector.spec.style.expression

/**
 * A parsed, type-checked expression node.
 *
 * Ported from `maplibre-style-spec/src/expression/expression.ts`.
 */
interface Expression {
    val type: ExprType

    fun evaluate(ctx: EvaluationContext): Any?

    fun eachChild(fn: (Expression) -> Unit)

    /**
     * Statically analyze the expression, attempting to enumerate possible outputs. Returns false if
     * the complete set of outputs is statically undecidable, otherwise true.
     */
    fun outputDefined(): Boolean
}

/**
 * A parser for one operator. Upstream models this as a static `parse` on the expression class;
 * Kotlin uses a function value held in the registry.
 *
 * Returning `null` means "a parsing error has already been pushed into the context".
 */
typealias ExpressionParser = (args: List<Any?>, context: ParsingContext) -> Expression?

/** Operator name to parser. Ported from `ExpressionRegistry`. */
typealias ExpressionRegistry = Map<String, ExpressionParser>
