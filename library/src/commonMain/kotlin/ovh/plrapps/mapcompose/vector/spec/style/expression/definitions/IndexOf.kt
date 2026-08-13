package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import ovh.plrapps.mapcompose.vector.spec.style.expression.BooleanType
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.NativeType
import ovh.plrapps.mapcompose.vector.spec.style.expression.NullType
import ovh.plrapps.mapcompose.vector.spec.style.expression.NumberType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.RuntimeError
import ovh.plrapps.mapcompose.vector.spec.style.expression.StringType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ValueType
import ovh.plrapps.mapcompose.vector.spec.style.expression.isValidNativeType
import ovh.plrapps.mapcompose.vector.spec.style.expression.isValidType
import ovh.plrapps.mapcompose.vector.spec.style.expression.jsIndexOf
import ovh.plrapps.mapcompose.vector.spec.style.expression.typeOf
import ovh.plrapps.mapcompose.vector.spec.style.expression.typeToString
import ovh.plrapps.mapcompose.vector.spec.style.expression.jsStringify

/** Ported from `maplibre-style-spec/src/expression/definitions/index_of.ts`. */
class IndexOf(
    val needle: Expression,
    val haystack: Expression,
    val key: String,
    val fromIndex: Expression? = null,
) : Expression {

    override val type: ExprType = NumberType

    override fun evaluate(ctx: EvaluationContext): Any {
        val needleValue = needle.evaluate(ctx)
        val haystackValue = haystack.evaluate(ctx)

        if (!isValidNativeType(
                needleValue,
                listOf(NativeType.BOOLEAN, NativeType.STRING, NativeType.NUMBER, NativeType.NULL),
            )
        ) {
            throw RuntimeError(
                "Expected first argument to be of type boolean, string, number or null, " +
                        "but found ${typeToString(typeOf(needleValue))} instead.",
                key,
            )
        }

        val from = (fromIndex?.evaluate(ctx) as? Number)?.toInt() ?: 0

        return when (haystackValue) {
            is String -> {
                val rawIndex = haystackValue.indexOf(jsStringify(needleValue), startIndex = from.coerceAtLeast(0))
                if (rawIndex == -1) {
                    -1.0
                } else {
                    // The index may be affected by surrogate pairs, so count code points.
                    haystackValue.substring(0, rawIndex).codePointCountCompat().toDouble()
                }
            }

            is List<*> -> jsIndexOf(haystackValue, needleValue, from).toDouble()

            else -> throw RuntimeError(
                "Expected second argument to be of type array or string, " +
                        "but found ${typeToString(typeOf(haystackValue))} instead.",
                key,
            )
        }
    }

    override fun eachChild(fn: (Expression) -> Unit) {
        fn(needle)
        fn(haystack)
        fromIndex?.let(fn)
    }

    override fun outputDefined(): Boolean = false

    companion object {
        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            if (args.size <= 2 || args.size >= 5) {
                return context.error("Expected 2 or 3 arguments, but found ${args.size - 1} instead.")
            }

            val needle = context.parse(args[1], 1, ValueType)
            val haystack = context.parse(args[2], 2, ValueType)
            if (needle == null || haystack == null) return null

            if (!isValidType(needle.type, listOf(BooleanType, StringType, NumberType, NullType, ValueType))) {
                return context.error(
                    "Expected first argument to be of type boolean, string, number or null, " +
                            "but found ${typeToString(needle.type)} instead"
                )
            }

            return if (args.size == 4) {
                val fromIndex = context.parse(args[3], 3, NumberType) ?: return null
                IndexOf(needle, haystack, context.key, fromIndex)
            } else {
                IndexOf(needle, haystack, context.key)
            }
        }
    }
}
