package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ResolvedImageType
import ovh.plrapps.mapcompose.vector.spec.style.expression.StringType
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.ResolvedImage

/** Ported from `maplibre-style-spec/src/expression/definitions/image.ts`. */
class Image(val input: Expression) : Expression {

    override val type: ExprType = ResolvedImageType

    override fun evaluate(ctx: EvaluationContext): Any? {
        val evaluatedImageName = input.evaluate(ctx) as? String
        val value = ResolvedImage.fromString(evaluatedImageName) ?: return null
        val available = ctx.availableImages?.contains(evaluatedImageName) ?: value.available
        return value.copy(available = available)
    }

    override fun eachChild(fn: (Expression) -> Unit) = fn(input)

    // The output depends on the list of available images in the evaluation context.
    override fun outputDefined(): Boolean = false

    companion object {
        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            if (args.size != 2) return context.error("Expected two arguments.")

            val name = context.parse(args[1], 1, StringType)
                ?: return context.error("No image name provided.")

            return Image(name)
        }
    }
}
