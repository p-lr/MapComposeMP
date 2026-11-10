package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.NumberType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.RuntimeError
import ovh.plrapps.mapcompose.vector.spec.style.expression.StringType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ValueType
import ovh.plrapps.mapcompose.vector.spec.style.expression.array
import ovh.plrapps.mapcompose.vector.spec.style.expression.isValidType
import ovh.plrapps.mapcompose.vector.spec.style.expression.jsSliceRange
import ovh.plrapps.mapcompose.vector.spec.style.expression.toCodePointStrings
import ovh.plrapps.mapcompose.vector.spec.style.expression.typeOf
import ovh.plrapps.mapcompose.vector.spec.style.expression.typeToString

/** Ported from `maplibre-style-spec/src/expression/definitions/slice.ts`. */
class Slice(
    override val type: ExprType,
    val input: Expression,
    val beginIndex: Expression,
    val key: String,
    val endIndex: Expression? = null,
) : Expression {

    override fun evaluate(ctx: EvaluationContext): Any {
        val inputValue = input.evaluate(ctx)
        val begin = (beginIndex.evaluate(ctx) as Number).toInt()
        val end = (endIndex?.evaluate(ctx) as? Number)?.toInt()

        return when (inputValue) {
            is String -> {
                // Indices may be affected by surrogate pairs.
                val cps = inputValue.toCodePointStrings()
                cps.slice(jsSliceRange(cps.size, begin, end)).joinToString(separator = "")
            }

            is List<*> -> inputValue.slice(jsSliceRange(inputValue.size, begin, end))

            else -> throw RuntimeError(
                "Expected first argument to be of type array or string, " +
                        "but found ${typeToString(typeOf(inputValue))} instead.",
                key,
            )
        }
    }

    override fun eachChild(fn: (Expression) -> Unit) {
        fn(input)
        fn(beginIndex)
        endIndex?.let(fn)
    }

    override fun outputDefined(): Boolean = false

    companion object {
        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            if (args.size <= 2 || args.size >= 5) {
                return context.error("Expected 2 or 3 arguments, but found ${args.size - 1} instead.")
            }

            val input = context.parse(args[1], 1, ValueType)
            val beginIndex = context.parse(args[2], 2, NumberType)
            if (input == null || beginIndex == null) return null

            if (!isValidType(input.type, listOf(array(ValueType), StringType, ValueType))) {
                return context.error(
                    "Expected first argument to be of type array or string, " +
                            "but found ${typeToString(input.type)} instead"
                )
            }

            return if (args.size == 4) {
                val endIndex = context.parse(args[3], 3, NumberType) ?: return null
                Slice(input.type, input, beginIndex, context.key, endIndex)
            } else {
                Slice(input.type, input, beginIndex, context.key)
            }
        }
    }
}
