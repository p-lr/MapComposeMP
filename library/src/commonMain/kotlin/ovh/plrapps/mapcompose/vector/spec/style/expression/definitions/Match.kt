package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ValueType
import ovh.plrapps.mapcompose.vector.spec.style.expression.formatNumber
import ovh.plrapps.mapcompose.vector.spec.style.expression.typeOf
import kotlin.math.abs
import kotlin.math.floor

/**
 * Ported from `maplibre-style-spec/src/expression/definitions/match.ts`.
 *
 * Branch labels are keyed by their JavaScript string form (`String(label)`), which is why numeric
 * labels must be integers: `1` and `1.0` would otherwise collide inconsistently.
 */
class Match(
    val inputType: ExprType,
    override val type: ExprType,
    val input: Expression,
    val cases: Map<String, Int>,
    val outputs: List<Expression>,
    val otherwise: Expression,
) : Expression {

    override fun evaluate(ctx: EvaluationContext): Any? {
        val inputValue = input.evaluate(ctx)
        val output = if (typeOf(inputValue) == inputType) {
            cases[labelKey(inputValue)]?.let { outputs[it] } ?: otherwise
        } else {
            otherwise
        }
        return output.evaluate(ctx)
    }

    override fun eachChild(fn: (Expression) -> Unit) {
        fn(input)
        outputs.forEach(fn)
        fn(otherwise)
    }

    override fun outputDefined(): Boolean = outputs.all { it.outputDefined() } && otherwise.outputDefined()

    companion object {
        private const val MAX_SAFE_INTEGER = 9007199254740991.0

        /** `String(label)` — the key the upstream `cases` object is indexed by. */
        private fun labelKey(label: Any?): String = when (label) {
            is Number -> formatNumber(label.toDouble())
            else -> label.toString()
        }

        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            if (args.size < 5) {
                return context.error("Expected at least 4 arguments, but found only ${args.size - 1}.")
            }
            if (args.size % 2 != 1) {
                return context.error("Expected an even number of arguments.")
            }

            var inputType: ExprType? = null
            var outputType: ExprType? = null
            if (context.expectedType != null && context.expectedType != ValueType) {
                outputType = context.expectedType
            }
            val cases = mutableMapOf<String, Int>()
            val outputs = mutableListOf<Expression>()

            var i = 2
            while (i < args.size - 1) {
                val rawLabels = args[i]
                val value = args[i + 1]

                val labels: List<Any?> = if (rawLabels is List<*>) rawLabels else listOf(rawLabels)

                val labelContext = context.concat(i)
                if (labels.isEmpty()) {
                    return labelContext.error("Expected at least one branch label.")
                }

                for (label in labels) {
                    if (label !is Number && label !is String) {
                        return labelContext.error("Branch labels must be numbers or strings.")
                    } else if (label is Number && abs(label.toDouble()) > MAX_SAFE_INTEGER) {
                        return labelContext.error(
                            "Branch labels must be integers no larger than ${formatNumber(MAX_SAFE_INTEGER)}."
                        )
                    } else if (label is Number && floor(label.toDouble()) != label.toDouble()) {
                        return labelContext.error("Numeric branch labels must be integer values.")
                    } else if (inputType == null) {
                        inputType = typeOf(label)
                    } else if (labelContext.checkSubtype(inputType, typeOf(label)) != null) {
                        return null
                    }

                    val key = labelKey(label)
                    if (cases.containsKey(key)) {
                        return labelContext.error("Branch labels must be unique.")
                    }
                    cases[key] = outputs.size
                }

                val result = context.parse(value, i, outputType) ?: return null
                if (outputType == null) outputType = result.type
                outputs.add(result)
                i += 2
            }

            val input = context.parse(args[1], 1, ValueType) ?: return null
            val otherwise = context.parse(args[args.size - 1], args.size - 1, outputType) ?: return null

            if (input.type != ValueType &&
                context.concat(1).checkSubtype(inputType!!, input.type) != null
            ) {
                return null
            }

            return Match(inputType!!, outputType!!, input, cases, outputs, otherwise)
        }
    }
}
