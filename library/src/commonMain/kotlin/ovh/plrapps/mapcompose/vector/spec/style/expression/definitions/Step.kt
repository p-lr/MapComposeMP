package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.NumberType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.Stops
import ovh.plrapps.mapcompose.vector.spec.style.expression.ValueType
import ovh.plrapps.mapcompose.vector.spec.style.expression.findStopLessThanOrEqualTo

/** Ported from `maplibre-style-spec/src/expression/definitions/step.ts`. */
class Step(
    override val type: ExprType,
    val input: Expression,
    stops: Stops,
    val key: String,
) : Expression {

    val labels: List<Double> = stops.map { it.first }
    val outputs: List<Expression> = stops.map { it.second }

    override fun evaluate(ctx: EvaluationContext): Any? {
        if (labels.size == 1) return outputs[0].evaluate(ctx)

        val value = (input.evaluate(ctx) as Number).toDouble()
        if (value <= labels[0]) return outputs[0].evaluate(ctx)

        val stopCount = labels.size
        if (value >= labels[stopCount - 1]) return outputs[stopCount - 1].evaluate(ctx)

        val index = findStopLessThanOrEqualTo(labels, value, key)
        return outputs[index].evaluate(ctx)
    }

    override fun eachChild(fn: (Expression) -> Unit) {
        fn(input)
        outputs.forEach(fn)
    }

    override fun outputDefined(): Boolean = outputs.all { it.outputDefined() }

    companion object {
        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            if (args.size - 1 < 4) {
                return context.error("Expected at least 4 arguments, but found only ${args.size - 1}.")
            }
            if ((args.size - 1) % 2 != 0) {
                return context.error("Expected an even number of arguments.")
            }

            val input = context.parse(args[1], 1, NumberType) ?: return null

            val stops = mutableListOf<Pair<Double, Expression>>()

            var outputType: ExprType? = null
            if (context.expectedType != null && context.expectedType != ValueType) {
                outputType = context.expectedType
            }

            var i = 1
            while (i < args.size) {
                val label = if (i == 1) Double.NEGATIVE_INFINITY else (args[i] as? Number)?.toDouble()
                val value = args[i + 1]

                val labelKey = i
                val valueKey = i + 1

                if (label == null) {
                    return context.error(
                        "Input/output pairs for \"step\" expressions must be defined using literal " +
                                "numeric values (not computed expressions) for the input values.",
                        labelKey,
                    )
                }

                if (stops.isNotEmpty() && stops.last().first >= label) {
                    return context.error(
                        "Input/output pairs for \"step\" expressions must be arranged with input " +
                                "values in strictly ascending order.",
                        labelKey,
                    )
                }

                val parsed = context.parse(value, valueKey, outputType) ?: return null
                if (outputType == null) outputType = parsed.type
                stops.add(label to parsed)
                i += 2
            }

            return Step(outputType!!, input, stops, context.key)
        }
    }
}
