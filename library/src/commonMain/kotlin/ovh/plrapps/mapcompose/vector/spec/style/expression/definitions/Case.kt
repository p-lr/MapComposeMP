package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import ovh.plrapps.mapcompose.vector.spec.style.expression.BooleanType
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ValueType

/** Ported from `maplibre-style-spec/src/expression/definitions/case.ts`. */
class Case(
    override val type: ExprType,
    val branches: List<Pair<Expression, Expression>>,
    val otherwise: Expression,
) : Expression {

    override fun evaluate(ctx: EvaluationContext): Any? {
        for ((test, expression) in branches) {
            if (test.evaluate(ctx) == true) {
                return expression.evaluate(ctx)
            }
        }
        return otherwise.evaluate(ctx)
    }

    override fun eachChild(fn: (Expression) -> Unit) {
        for ((test, expression) in branches) {
            fn(test)
            fn(expression)
        }
        fn(otherwise)
    }

    override fun outputDefined(): Boolean =
        branches.all { it.second.outputDefined() } && otherwise.outputDefined()

    companion object {
        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            if (args.size < 4) {
                return context.error("Expected at least 3 arguments, but found only ${args.size - 1}.")
            }
            if (args.size % 2 != 0) {
                return context.error("Expected an odd number of arguments.")
            }

            var outputType: ExprType? = null
            if (context.expectedType != null && context.expectedType != ValueType) {
                outputType = context.expectedType
            }

            val branches = mutableListOf<Pair<Expression, Expression>>()
            var i = 1
            while (i < args.size - 1) {
                val test = context.parse(args[i], i, BooleanType) ?: return null
                val result = context.parse(args[i + 1], i + 1, outputType) ?: return null
                branches.add(test to result)
                if (outputType == null) outputType = result.type
                i += 2
            }

            val otherwise = context.parse(args[args.size - 1], args.size - 1, outputType) ?: return null

            val inferred = outputType ?: error("Can't infer output type")
            return Case(inferred, branches, otherwise)
        }
    }
}
