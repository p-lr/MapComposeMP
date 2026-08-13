package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import ovh.plrapps.mapcompose.vector.spec.style.expression.ArrayType
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.NumberType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.RuntimeError
import ovh.plrapps.mapcompose.vector.spec.style.expression.ValueType
import ovh.plrapps.mapcompose.vector.spec.style.expression.array
import ovh.plrapps.mapcompose.vector.spec.style.expression.formatNumber
import kotlin.math.floor

/** Ported from `maplibre-style-spec/src/expression/definitions/at.ts`. */
class At(
    override val type: ExprType,
    val index: Expression,
    val input: Expression,
    val key: String,
) : Expression {

    override fun evaluate(ctx: EvaluationContext): Any? {
        val i = (index.evaluate(ctx) as Number).toDouble()
        val arr = input.evaluate(ctx) as List<*>

        if (i < 0) throw RuntimeError("Array index out of bounds: ${formatNumber(i)} < 0.", key)
        if (i >= arr.size) {
            throw RuntimeError("Array index out of bounds: ${formatNumber(i)} > ${arr.size - 1}.", key)
        }
        if (i != floor(i)) {
            throw RuntimeError("Array index must be an integer, but found ${formatNumber(i)} instead.", key)
        }

        return arr[i.toInt()]
    }

    override fun eachChild(fn: (Expression) -> Unit) {
        fn(index)
        fn(input)
    }

    override fun outputDefined(): Boolean = false

    companion object {
        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            if (args.size != 3) {
                return context.error("Expected 2 arguments, but found ${args.size - 1} instead.")
            }

            val index = context.parse(args[1], 1, NumberType)
            val input = context.parse(args[2], 2, array(context.expectedType ?: ValueType))
            if (index == null || input == null) return null

            val t = input.type as ArrayType
            return At(t.itemType, index, input, context.key)
        }
    }
}
