package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import ovh.plrapps.mapcompose.vector.spec.style.expression.BooleanType
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.NumberType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ObjectType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.RuntimeError
import ovh.plrapps.mapcompose.vector.spec.style.expression.StringType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ValueType
import ovh.plrapps.mapcompose.vector.spec.style.expression.array
import ovh.plrapps.mapcompose.vector.spec.style.expression.checkSubtype
import ovh.plrapps.mapcompose.vector.spec.style.expression.typeOf
import ovh.plrapps.mapcompose.vector.spec.style.expression.typeToString

/**
 * The `string` / `number` / `boolean` / `object` / `array` assertions.
 *
 * Ported from `maplibre-style-spec/src/expression/definitions/assertion.ts`. The parser also
 * inserts these implicitly, which is what lets a `["get", …]` of unknown type be used where a
 * number is expected.
 */
class Assertion(
    override val type: ExprType,
    val args: List<Expression>,
    val key: String,
) : Expression {

    override fun evaluate(ctx: EvaluationContext): Any? {
        for (i in args.indices) {
            val value = args[i].evaluate(ctx)
            val error = checkSubtype(type, typeOf(value))
            if (error == null) {
                return value
            } else if (i == args.size - 1) {
                throw RuntimeError(
                    "Expected value to be of type ${typeToString(type)}, " +
                            "but found ${typeToString(typeOf(value))} instead.",
                    key,
                )
            }
        }
        throw RuntimeError("Expected at least one argument.", key)
    }

    override fun eachChild(fn: (Expression) -> Unit) = args.forEach(fn)

    override fun outputDefined(): Boolean = args.all { it.outputDefined() }

    companion object {
        private val types: Map<String, ExprType> = mapOf(
            "string" to StringType,
            "number" to NumberType,
            "boolean" to BooleanType,
            "object" to ObjectType,
        )

        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            if (args.size < 2) return context.error("Expected at least one argument.")

            var i = 1
            val type: ExprType

            when (val name = args[0] as? String) {
                "array" -> {
                    val itemType: ExprType
                    if (args.size > 2) {
                        val t = args[1]
                        if (t !is String || t !in types || t == "object") {
                            return context.error(
                                "The item type argument of \"array\" must be one of string, number, boolean",
                                1,
                            )
                        }
                        itemType = types.getValue(t)
                        i++
                    } else {
                        itemType = ValueType
                    }

                    var n: Int? = null
                    if (args.size > 3) {
                        val lengthArg = args[2]
                        if (lengthArg != null &&
                            (lengthArg !is Number || lengthArg.toDouble() < 0 ||
                                    lengthArg.toDouble() != kotlin.math.floor(lengthArg.toDouble()))
                        ) {
                            return context.error(
                                "The length argument to \"array\" must be a positive integer literal",
                                2,
                            )
                        }
                        n = (lengthArg as? Number)?.toInt()
                        i++
                    }

                    type = array(itemType, n)
                }

                else -> {
                    type = types[name] ?: return context.error("Types doesn't contain name = $name")
                }
            }

            val parsed = mutableListOf<Expression>()
            while (i < args.size) {
                val input = context.parse(args[i], i, ValueType) ?: return null
                parsed.add(input)
                i++
            }

            return Assertion(type, parsed, context.key)
        }
    }
}
