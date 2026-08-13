package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ValueType

/** Ported from `maplibre-style-spec/src/expression/definitions/global_state.ts`. */
class GlobalState(val stateKey: String) : Expression {

    override val type: ExprType = ValueType

    override fun evaluate(ctx: EvaluationContext): Any? {
        val globalState = ctx.globals?.globalState
        if (globalState.isNullOrEmpty()) return null
        return globalState[stateKey]
    }

    override fun eachChild(fn: (Expression) -> Unit) = Unit

    override fun outputDefined(): Boolean = false

    companion object {
        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            if (args.size != 2) {
                return context.error("Expected 1 argument, but found ${args.size - 1} instead.")
            }

            val key = args[1]
                ?: return context.error("Global state property must be defined.")

            if (key !is String) {
                return context.error(
                    "Global state property must be string, but found ${
                        when (key) {
                            is Boolean -> "boolean"
                            is Number -> "number"
                            else -> "object"
                        }
                    } instead."
                )
            }

            return GlobalState(key)
        }
    }
}
