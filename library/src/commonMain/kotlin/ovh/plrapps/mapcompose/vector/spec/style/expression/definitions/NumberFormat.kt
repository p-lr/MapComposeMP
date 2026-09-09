package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.NumberType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.StringType

/**
 * Ported from `maplibre-style-spec/src/expression/definitions/number_format.ts`.
 *
 * Upstream delegates to `Intl.NumberFormat`; so does this, through [formatNumberPlatform] — every
 * target reaches CLDR through its own API, so `locale` genuinely selects grouping and decimal
 * separators, `currency` produces a real symbol at the locale's placement with that currency's own
 * digit count, and the fraction-digit options behave as ECMA-402 specifies them.
 *
 * Two divergences remain, both documented where they are made: unit names come from a shared
 * English table ([formatWithUnit]) because no JVM API exposes CLDR's, and an option combination
 * `Intl.NumberFormat` would reject is clamped rather than thrown ([formatNumber]). A third is
 * inherent: with no `locale` the output follows the *host's* default locale, so it differs between
 * platforms exactly as upstream's differs between browsers.
 *
 * A platform formatter is built per evaluation and none is cached. That is not a missed
 * optimization: one `StyleExpression` is shared by the whole tile-worker pool, so nothing under
 * `spec/style/expression` may hold state across an `evaluate` call, and neither
 * `java.text.NumberFormat` nor `NSNumberFormatter` is safe to share between threads.
 */
class NumberFormat(
    val number: Expression,
    val locale: Expression?,
    val currency: Expression?,
    val unit: Expression?,
    val minFractionDigits: Expression?,
    val maxFractionDigits: Expression?,
) : Expression {

    override val type: ExprType = StringType

    override fun evaluate(ctx: EvaluationContext): Any = formatNumber(
        value = (number.evaluate(ctx) as Number).toDouble(),
        locale = locale?.evaluate(ctx) as? String,
        currency = currency?.evaluate(ctx) as? String,
        unit = unit?.evaluate(ctx) as? String,
        minFractionDigits = (minFractionDigits?.evaluate(ctx) as? Number)?.toInt(),
        maxFractionDigits = (maxFractionDigits?.evaluate(ctx) as? Number)?.toInt(),
    )

    override fun eachChild(fn: (Expression) -> Unit) {
        fn(number)
        locale?.let(fn)
        currency?.let(fn)
        unit?.let(fn)
        minFractionDigits?.let(fn)
        maxFractionDigits?.let(fn)
    }

    override fun outputDefined(): Boolean = false

    companion object {
        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            if (args.size != 3) return context.error("Expected two arguments.")

            val number = context.parse(args[1], 1, NumberType) ?: return null

            val options = args[2]
            if (options !is Map<*, *>) {
                return context.error("NumberFormat options argument must be an object.")
            }

            var locale: Expression? = null
            if (options["locale"] != null) {
                locale = context.parse(options["locale"], 1, StringType) ?: return null
            }

            var currency: Expression? = null
            if (options["currency"] != null) {
                currency = context.parse(options["currency"], 1, StringType) ?: return null
            }

            var unit: Expression? = null
            if (options["unit"] != null) {
                unit = context.parse(options["unit"], 1, StringType) ?: return null
            }

            if (currency != null && unit != null) {
                return context.error("NumberFormat options `currency` and `unit` are mutually exclusive")
            }

            var minFractionDigits: Expression? = null
            if (options["min-fraction-digits"] != null) {
                minFractionDigits = context.parse(options["min-fraction-digits"], 1, NumberType) ?: return null
            }

            var maxFractionDigits: Expression? = null
            if (options["max-fraction-digits"] != null) {
                maxFractionDigits = context.parse(options["max-fraction-digits"], 1, NumberType) ?: return null
            }

            return NumberFormat(number, locale, currency, unit, minFractionDigits, maxFractionDigits)
        }
    }
}
