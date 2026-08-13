package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import ovh.plrapps.mapcompose.vector.spec.style.expression.ArrayType
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.NumberType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.RuntimeError
import ovh.plrapps.mapcompose.vector.spec.style.expression.StringType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ValueType
import ovh.plrapps.mapcompose.vector.spec.style.expression.typeOf
import ovh.plrapps.mapcompose.vector.spec.style.expression.typeToString

/** Ported from `maplibre-style-spec/src/expression/definitions/length.ts`. */
class Length(val input: Expression, val key: String) : Expression {

    override val type: ExprType = NumberType

    override fun evaluate(ctx: EvaluationContext): Any {
        return when (val value = input.evaluate(ctx)) {
            // JavaScript's [...string].length counts code points, not UTF-16 units.
            is String -> value.codePointCountCompat().toDouble()
            is List<*> -> value.size.toDouble()
            else -> throw RuntimeError(
                "Expected value to be of type string or array, but found ${typeToString(typeOf(value))} instead.",
                key,
            )
        }
    }

    override fun eachChild(fn: (Expression) -> Unit) = fn(input)

    override fun outputDefined(): Boolean = false

    companion object {
        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            if (args.size != 2) {
                return context.error("Expected 1 argument, but found ${args.size - 1} instead.")
            }

            val input = context.parse(args[1], 1) ?: return null

            if (input.type !is ArrayType && input.type != StringType && input.type != ValueType) {
                return context.error(
                    "Expected argument of type string or array, but found ${typeToString(input.type)} instead."
                )
            }

            return Length(input, context.key)
        }
    }
}

/** Counts Unicode code points, matching JavaScript's `[...str].length`. */
internal fun String.codePointCountCompat(): Int {
    var count = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        i += if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}
