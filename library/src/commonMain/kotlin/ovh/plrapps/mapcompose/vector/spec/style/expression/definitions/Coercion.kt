package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.vector.spec.style.expression.BooleanType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ColorType
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.FormattedType
import ovh.plrapps.mapcompose.vector.spec.style.expression.NumberType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ResolvedImageType
import ovh.plrapps.mapcompose.vector.spec.style.expression.RuntimeError
import ovh.plrapps.mapcompose.vector.spec.style.expression.StringType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ValueType
import ovh.plrapps.mapcompose.vector.spec.style.expression.jsToNumber
import ovh.plrapps.mapcompose.vector.spec.style.expression.jsTruthy
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.Formatted
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.ResolvedImage
import ovh.plrapps.mapcompose.vector.spec.style.expression.validateRGBA
import ovh.plrapps.mapcompose.vector.spec.style.expression.valueToJsonString
import ovh.plrapps.mapcompose.vector.spec.style.expression.valueToString

/**
 * Special form for error-coalescing coercion expressions `to-number`, `to-color`.
 *
 * Ported from `maplibre-style-spec/src/expression/definitions/coercion.ts`. Since these coercions
 * can fail at runtime they accept multiple arguments, evaluating one at a time until one succeeds.
 * The parser also inserts these implicitly for color- and formatted-typed properties.
 */
class Coercion(
    override val type: ExprType,
    val args: List<Expression>,
    val key: String,
) : Expression {

    override fun evaluate(ctx: EvaluationContext): Any? = when (type) {
        BooleanType -> jsTruthy(args[0].evaluate(ctx))

        ColorType -> {
            var input: Any? = null
            var error: String? = null
            var result: Color? = null
            for (arg in args) {
                input = arg.evaluate(ctx)
                error = null
                if (input is Color) {
                    result = input
                    break
                } else if (input is String) {
                    val c = ctx.parseColor(input)
                    if (c != null) {
                        result = c
                        break
                    }
                } else if (input is List<*>) {
                    error = if (input.size < 3 || input.size > 4) {
                        "Invalid rgba value ${valueToJsonString(input)}: expected an array " +
                                "containing either three or four numeric values."
                    } else {
                        validateRGBA(input[0], input[1], input[2], input.getOrNull(3))
                    }
                    if (error == null) {
                        result = Color(
                            red = ((input[0] as Number).toDouble() / 255.0).toFloat(),
                            green = ((input[1] as Number).toDouble() / 255.0).toFloat(),
                            blue = ((input[2] as Number).toDouble() / 255.0).toFloat(),
                            alpha = ((input.getOrNull(3) as? Number)?.toDouble() ?: 1.0).toFloat(),
                        )
                        break
                    }
                }
            }
            result ?: throw RuntimeError(
                error ?: "Could not parse color from value '${
                    if (input is String) input else valueToJsonString(input)
                }'",
                key,
            )
        }

        NumberType -> {
            var value: Any? = null
            var result: Double? = null
            for (arg in args) {
                value = arg.evaluate(ctx)
                if (value == null) {
                    result = 0.0
                    break
                }
                val num = jsToNumber(value)
                if (num.isNaN()) continue
                result = num
                break
            }
            result ?: throw RuntimeError("Could not convert ${valueToJsonString(value)} to number.", key)
        }

        FormattedType -> Formatted.fromString(valueToString(args[0].evaluate(ctx)))

        ResolvedImageType -> ResolvedImage.fromString(valueToString(args[0].evaluate(ctx)))

        else -> valueToString(args[0].evaluate(ctx))
    }

    override fun eachChild(fn: (Expression) -> Unit) = args.forEach(fn)

    override fun outputDefined(): Boolean = args.all { it.outputDefined() }

    companion object {
        private val types: Map<String, ExprType> = mapOf(
            "to-boolean" to BooleanType,
            "to-color" to ColorType,
            "to-number" to NumberType,
            "to-string" to StringType,
        )

        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            if (args.size < 2) return context.error("Expected at least one argument.")

            val name = args[0] as? String
            val type = types[name] ?: return context.error("Can't parse $name as it is not part of the known types")
            if ((name == "to-boolean" || name == "to-string") && args.size != 2) {
                return context.error("Expected one argument.")
            }

            val parsed = mutableListOf<Expression>()
            for (i in 1 until args.size) {
                val input = context.parse(args[i], i, ValueType) ?: return null
                parsed.add(input)
            }

            return Coercion(type, parsed, context.key)
        }
    }
}
