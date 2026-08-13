package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import ovh.plrapps.mapcompose.vector.spec.style.expression.ArrayType
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ValueType
import ovh.plrapps.mapcompose.vector.spec.style.expression.array
import ovh.plrapps.mapcompose.vector.spec.style.expression.isValue
import ovh.plrapps.mapcompose.vector.spec.style.expression.typeOf

/**
 * Like `literal`, but the array elements may themselves be expressions.
 *
 * Ported from `maplibre-style-spec/src/expression/definitions/semiliteral.ts`.
 */
class Semiliteral(val arr: List<Expression>) : Expression {

    override val type: ExprType = run {
        var elementType: ExprType? = null
        for (expr in arr) {
            if (elementType == null) {
                elementType = expr.type
            } else if (elementType == expr.type) {
                continue
            } else {
                elementType = ValueType
                break
            }
        }
        array(elementType ?: ValueType, arr.size)
    }

    override fun evaluate(ctx: EvaluationContext): Any = arr.map { it.evaluate(ctx) }

    override fun eachChild(fn: (Expression) -> Unit) = arr.forEach(fn)

    override fun outputDefined(): Boolean = arr.all { it.outputDefined() }

    companion object {
        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            if (args.size != 2) {
                return context.error(
                    "'semiliteral' expression requires exactly one argument, but found ${args.size - 1} instead."
                )
            }

            if (!isValue(args[1])) {
                return context.error("invalid value of type \"${jsTypeNameOf(args[1])}\"")
            }

            val value = args[1]
            val type = typeOf(value)

            return if (type is ArrayType) {
                val parsed = (value as List<*>).map { item ->
                    context.parse(item, null, ValueType) ?: return null
                }
                Semiliteral(parsed)
            } else {
                Literal(type, value)
            }
        }

        private fun jsTypeNameOf(v: Any?): String = when (v) {
            null -> "object"
            is String -> "string"
            is Boolean -> "boolean"
            is Number -> "number"
            else -> "object"
        }
    }
}
