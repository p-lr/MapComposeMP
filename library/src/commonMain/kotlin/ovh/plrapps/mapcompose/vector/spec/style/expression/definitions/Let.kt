package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext

/** Ported from `maplibre-style-spec/src/expression/definitions/let.ts`. */
class Let(val bindings: List<Pair<String, Expression>>, val result: Expression) : Expression {

    override val type: ExprType = result.type

    override fun evaluate(ctx: EvaluationContext): Any? = result.evaluate(ctx)

    override fun eachChild(fn: (Expression) -> Unit) {
        for (binding in bindings) fn(binding.second)
        fn(result)
    }

    override fun outputDefined(): Boolean = result.outputDefined()

    companion object {
        private val INVALID_NAME = Regex("[^a-zA-Z0-9_]")

        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            if (args.size < 4) {
                return context.error("Expected at least 3 arguments, but found ${args.size - 1} instead.")
            }

            val bindings = mutableListOf<Pair<String, Expression>>()
            var i = 1
            while (i < args.size - 1) {
                val name = args[i]
                if (name !is String) {
                    return context.error("Expected string, but found ${jsTypeName(name)} instead.", i)
                }
                if (INVALID_NAME.containsMatchIn(name)) {
                    return context.error(
                        "Variable names must contain only alphanumeric characters or '_'.",
                        i,
                    )
                }
                val value = context.parse(args[i + 1], i + 1) ?: return null
                bindings.add(name to value)
                i += 2
            }

            val result = context.parse(
                args[args.size - 1],
                args.size - 1,
                context.expectedType,
                bindings,
            ) ?: return null

            return Let(bindings, result)
        }

        private fun jsTypeName(v: Any?): String = when (v) {
            null -> "object"
            is String -> "string"
            is Boolean -> "boolean"
            is Number -> "number"
            else -> "object"
        }
    }
}
