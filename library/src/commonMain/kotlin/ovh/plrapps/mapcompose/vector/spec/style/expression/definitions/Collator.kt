package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import ovh.plrapps.mapcompose.vector.spec.style.expression.BooleanType
import ovh.plrapps.mapcompose.vector.spec.style.expression.CollatorType
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.StringType
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.Collator as CollatorValue

/** Ported from `maplibre-style-spec/src/expression/definitions/collator.ts`. */
class Collator(
    val caseSensitive: Expression,
    val diacriticSensitive: Expression,
    val locale: Expression?,
) : Expression {

    override val type: ExprType = CollatorType

    override fun evaluate(ctx: EvaluationContext): Any = CollatorValue(
        caseSensitive = caseSensitive.evaluate(ctx) as Boolean,
        diacriticSensitive = diacriticSensitive.evaluate(ctx) as Boolean,
        locale = locale?.evaluate(ctx) as String?,
    )

    override fun eachChild(fn: (Expression) -> Unit) {
        fn(caseSensitive)
        fn(diacriticSensitive)
        locale?.let(fn)
    }

    // Technically the set of possible outputs is the combinatoric set of Collators produced by all
    // possible outputs of locale/caseSensitive/diacriticSensitive, but the comparison operators
    // ignore a Collator's possible outputs anyway.
    override fun outputDefined(): Boolean = false

    companion object {
        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            if (args.size != 2) return context.error("Expected one argument.")

            val options = args[1]
            if (options !is Map<*, *>) {
                return context.error("Collator options argument must be an object.")
            }

            val caseSensitive = context.parse(options["case-sensitive"] ?: false, 1, BooleanType)
                ?: return null
            val diacriticSensitive = context.parse(options["diacritic-sensitive"] ?: false, 1, BooleanType)
                ?: return null

            var locale: Expression? = null
            if (options["locale"] != null) {
                locale = context.parse(options["locale"], 1, StringType) ?: return null
            }

            return Collator(caseSensitive, diacriticSensitive, locale)
        }
    }
}
