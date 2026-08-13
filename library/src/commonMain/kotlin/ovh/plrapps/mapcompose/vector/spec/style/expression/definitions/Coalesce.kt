package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ValueType
import ovh.plrapps.mapcompose.vector.spec.style.expression.checkSubtype
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.ResolvedImage

/** Ported from `maplibre-style-spec/src/expression/definitions/coalesce.ts`. */
class Coalesce(override val type: ExprType, val args: List<Expression>) : Expression {

    override fun evaluate(ctx: EvaluationContext): Any? {
        var result: Any? = null
        var argCount = 0
        var requestedImageName: String? = null
        for (arg in args) {
            argCount++
            result = arg.evaluate(ctx)
            // Keep track of the first requested image: if coalesce can't find a valid one we return
            // its name so the host can report a missing image.
            if (result is ResolvedImage && !result.available) {
                if (requestedImageName == null) requestedImageName = result.name
                result = null
                if (argCount == args.size) result = requestedImageName
            }
            if (result != null) break
        }
        return result
    }

    override fun eachChild(fn: (Expression) -> Unit) = args.forEach(fn)

    override fun outputDefined(): Boolean = args.all { it.outputDefined() }

    companion object {
        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            if (args.size < 2) return context.error("Expected at least one argument.")

            var outputType: ExprType? = null
            val expectedType = context.expectedType
            if (expectedType != null && expectedType != ValueType) {
                outputType = expectedType
            }
            val parsedArgs = mutableListOf<Expression>()

            for (arg in args.drop(1)) {
                val parsed = context.parse(
                    arg,
                    1 + parsedArgs.size,
                    outputType,
                    typeAnnotation = ParsingContext.TypeAnnotation.OMIT,
                ) ?: return null
                if (outputType == null) outputType = parsed.type
                parsedArgs.add(parsed)
            }
            val inferred = outputType ?: error("No output type")

            // Arguments are parsed without inferred type annotation so that they don't produce a
            // runtime error for `null` input, which would preempt the null-coalescing behaviour.
            // If any argument would have needed an annotation, the enclosing coalesce is wrapped
            // instead.
            val needsAnnotation = expectedType != null &&
                    parsedArgs.any { checkSubtype(expectedType, it.type) != null }

            return if (needsAnnotation) Coalesce(ValueType, parsedArgs) else Coalesce(inferred, parsedArgs)
        }
    }
}
