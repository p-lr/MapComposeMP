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
import ovh.plrapps.mapcompose.vector.spec.style.expression.jsTruthy
import ovh.plrapps.mapcompose.vector.spec.style.expression.typeOf
import ovh.plrapps.mapcompose.vector.spec.style.expression.typeToString
import ovh.plrapps.mapcompose.vector.spec.style.expression.jsStringify

/** Ported from `maplibre-style-spec/src/expression/definitions/in.ts`. */
class In(val needle: Expression, val haystack: Expression, val key: String) : Expression {

    override val type: ExprType = BooleanType

    override fun evaluate(ctx: EvaluationContext): Any {
        val needleValue = needle.evaluate(ctx)
        val haystackValue = haystack.evaluate(ctx)

        if (!jsTruthy(haystackValue)) return false

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

        if (!isValidNativeType(haystackValue, listOf(NativeType.STRING, NativeType.ARRAY))) {
            throw RuntimeError(
                "Expected second argument to be of type array or string, " +
                        "but found ${typeToString(typeOf(haystackValue))} instead.",
                key,
            )
        }

        return when (haystackValue) {
            is String -> haystackValue.contains(jsStringify(needleValue))
            is List<*> -> jsIndexOf(haystackValue, needleValue) >= 0
            else -> false
        }
    }

    override fun eachChild(fn: (Expression) -> Unit) {
        fn(needle)
        fn(haystack)
    }

    override fun outputDefined(): Boolean = true

    companion object {
        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            if (args.size != 3) {
                return context.error("Expected 2 arguments, but found ${args.size - 1} instead.")
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

            return In(needle, haystack, context.key)
        }
    }
}
