package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import ovh.plrapps.mapcompose.vector.spec.style.expression.ArrayType
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.isValue
import ovh.plrapps.mapcompose.vector.spec.style.expression.typeOf
import ovh.plrapps.mapcompose.vector.spec.style.expression.valueToJsonString

/** Ported from `maplibre-style-spec/src/expression/definitions/literal.ts`. */
class Literal(override val type: ExprType, val value: Any?) : Expression {

    override fun evaluate(ctx: EvaluationContext): Any? = value

    override fun eachChild(fn: (Expression) -> Unit) = Unit

    override fun outputDefined(): Boolean = true

    companion object {
        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            if (args.size != 2) {
                return context.error(
                    "'literal' expression requires exactly one argument, but found ${args.size - 1} instead."
                )
            }

            if (!isValue(args[1])) {
                return context.error("invalid value of type \"${jsTypeName(args[1])}\"")
            }

            val value = args[1]
            var type = typeOf(value)

            // special case: infer the item type if possible for zero-length arrays
            val expected = context.expectedType
            if (type is ArrayType && type.n == 0 &&
                expected is ArrayType && (expected.n == null || expected.n == 0)
            ) {
                type = expected
            }

            return Literal(type, value)
        }

        private fun jsTypeName(v: Any?): String = when (v) {
            null -> "object"
            is String -> "string"
            is Boolean -> "boolean"
            is Number -> "number"
            else -> "object"
        }
    }

    override fun toString(): String = "Literal(${valueToJsonString(value)})"
}
