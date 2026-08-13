package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.vector.spec.style.expression.ArrayType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ColorType
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.NumberType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.Stops
import ovh.plrapps.mapcompose.vector.spec.style.expression.UnitBezier
import ovh.plrapps.mapcompose.vector.spec.style.expression.ValueType
import ovh.plrapps.mapcompose.vector.spec.style.expression.colorspaces.InterpolationColorSpace
import ovh.plrapps.mapcompose.vector.spec.style.expression.colorspaces.interpolateColor
import ovh.plrapps.mapcompose.vector.spec.style.expression.colorspaces.interpolateNumber
import ovh.plrapps.mapcompose.vector.spec.style.expression.findStopLessThanOrEqualTo
import ovh.plrapps.mapcompose.vector.spec.style.expression.typeToString
import kotlin.math.pow

/** The interpolation curve of an `interpolate` expression. */
sealed class InterpolationType {
    data object Linear : InterpolationType()
    data class Exponential(val base: Double) : InterpolationType()
    data class CubicBezier(val controlPoints: List<Double>) : InterpolationType()
}

/** Ported from `maplibre-style-spec/src/expression/definitions/interpolate.ts`. */
class Interpolate(
    override val type: ExprType,
    val operator: String,
    val interpolation: InterpolationType,
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
        val lower = labels[index]
        val upper = labels[index + 1]
        val t = interpolationFactor(interpolation, value, lower, upper)

        val outputLower = outputs[index].evaluate(ctx)
        val outputUpper = outputs[index + 1].evaluate(ctx)

        val space = when (operator) {
            "interpolate-hcl" -> InterpolationColorSpace.HCL
            "interpolate-lab" -> InterpolationColorSpace.LAB
            else -> InterpolationColorSpace.RGB
        }

        return when {
            type == NumberType -> interpolateNumber(
                (outputLower as Number).toDouble(),
                (outputUpper as Number).toDouble(),
                t,
            )

            type == ColorType -> interpolateColor(outputLower as Color, outputUpper as Color, t, space)

            type is ArrayType -> {
                val a = outputLower as List<*>
                val b = outputUpper as List<*>
                a.mapIndexed { i, v ->
                    interpolateNumber((v as Number).toDouble(), (b[i] as Number).toDouble(), t)
                }
            }

            else -> null
        }
    }

    override fun eachChild(fn: (Expression) -> Unit) {
        fn(input)
        outputs.forEach(fn)
    }

    override fun outputDefined(): Boolean = outputs.all { it.outputDefined() }

    companion object {
        fun interpolationFactor(
            interpolation: InterpolationType,
            input: Double,
            lower: Double,
            upper: Double,
        ): Double = when (interpolation) {
            is InterpolationType.Exponential -> exponentialInterpolation(input, interpolation.base, lower, upper)
            is InterpolationType.Linear -> exponentialInterpolation(input, 1.0, lower, upper)
            is InterpolationType.CubicBezier -> {
                val c = interpolation.controlPoints
                UnitBezier(c[0], c[1], c[2], c[3]).solve(exponentialInterpolation(input, 1.0, lower, upper))
            }
        }

        /**
         * Returns the ratio used to interpolate between two exponential stops.
         *
         * Two consecutive stops define a scaled and shifted exponential `f(x) = a * base^x + b`;
         * the algebra in the upstream comment reduces the ratio to
         * `(base^(x-x0) - 1) / (base^(x1-x0) - 1)`.
         */
        private fun exponentialInterpolation(
            input: Double,
            base: Double,
            lowerValue: Double,
            upperValue: Double,
        ): Double {
            val difference = upperValue - lowerValue
            val progress = input - lowerValue

            return when {
                difference == 0.0 -> 0.0
                base == 1.0 -> progress / difference
                else -> (base.pow(progress) - 1) / (base.pow(difference) - 1)
            }
        }

        private val INTERPOLATABLE_TYPES = listOf(NumberType, ColorType)

        fun parser(operator: String): (List<Any?>, ParsingContext) -> Expression? = { args, context ->
            parse(operator, args, context)
        }

        private fun parse(operator: String, args: List<Any?>, context: ParsingContext): Expression? {
            val interpolationArg = args.getOrNull(1)

            if (interpolationArg !is List<*> || interpolationArg.isEmpty()) {
                return context.error("Expected an interpolation type expression.", 1)
            }

            val interpolation: InterpolationType = when (interpolationArg[0]) {
                "linear" -> InterpolationType.Linear
                "exponential" -> {
                    val base = interpolationArg.getOrNull(1) as? Number
                        ?: return context.error("Exponential interpolation requires a numeric base.", 1, 1)
                    InterpolationType.Exponential(base.toDouble())
                }

                "cubic-bezier" -> {
                    val controlPoints = interpolationArg.drop(1)
                    if (controlPoints.size != 4 ||
                        controlPoints.any { it !is Number || it.toDouble() < 0 || it.toDouble() > 1 }
                    ) {
                        return context.error(
                            "Cubic bezier interpolation requires four numeric arguments with values " +
                                    "between 0 and 1.",
                            1,
                        )
                    }
                    InterpolationType.CubicBezier(controlPoints.map { (it as Number).toDouble() })
                }

                else -> return context.error("Unknown interpolation type ${interpolationArg[0]}", 1, 0)
            }

            if (args.size - 1 < 4) {
                return context.error("Expected at least 4 arguments, but found only ${args.size - 1}.")
            }
            if ((args.size - 1) % 2 != 0) {
                return context.error("Expected an even number of arguments.")
            }

            val input = context.parse(args[2], 2, NumberType) ?: return null

            val stops = mutableListOf<Pair<Double, Expression>>()

            var outputType: ExprType? = null
            if (operator == "interpolate-hcl" || operator == "interpolate-lab") {
                outputType = ColorType
            } else if (context.expectedType != null && context.expectedType != ValueType) {
                outputType = context.expectedType
            }

            val rest = args.drop(3)
            var i = 0
            while (i < rest.size) {
                val label = (rest[i] as? Number)?.toDouble()
                val value = rest[i + 1]

                val labelKey = i + 3
                val valueKey = i + 4

                if (label == null) {
                    return context.error(
                        "Input/output pairs for \"interpolate\" expressions must be defined using " +
                                "literal numeric values (not computed expressions) for the input values.",
                        labelKey,
                    )
                }

                if (stops.isNotEmpty() && stops.last().first >= label) {
                    return context.error(
                        "Input/output pairs for \"interpolate\" expressions must be arranged with " +
                                "input values in strictly ascending order.",
                        labelKey,
                    )
                }

                val parsed = context.parse(value, valueKey, outputType) ?: return null
                if (outputType == null) outputType = parsed.type
                stops.add(label to parsed)
                i += 2
            }

            val resolved = outputType!!
            val interpolatable = resolved in INTERPOLATABLE_TYPES ||
                    (resolved is ArrayType && resolved.itemType == NumberType && resolved.n != null)
            if (!interpolatable) {
                return context.error("Type ${typeToString(resolved)} is not interpolatable.")
            }

            return Interpolate(resolved, operator, interpolation, input, stops, context.key)
        }
    }
}
