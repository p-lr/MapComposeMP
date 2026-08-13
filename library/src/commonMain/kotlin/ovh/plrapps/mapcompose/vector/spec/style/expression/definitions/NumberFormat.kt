package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.NumberType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.StringType
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Ported from `maplibre-style-spec/src/expression/definitions/number_format.ts`.
 *
 * **Divergence from MapLibre.** Upstream delegates to `Intl.NumberFormat`, which Kotlin
 * Multiplatform has no equivalent of. This implementation is locale-agnostic and reproduces only
 * the parts that are locale-independent:
 * - `min-fraction-digits` / `max-fraction-digits` (defaults 0 and 3, as in ECMA-402)
 * - grouping separators are **not** inserted
 * - `currency` and `unit` are appended as a suffix rather than formatted per CLDR
 *
 * `locale` is parsed and type-checked so styles using it still load, but does not affect output.
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

    override fun evaluate(ctx: EvaluationContext): Any {
        val value = (number.evaluate(ctx) as Number).toDouble()
        val minDigits = (minFractionDigits?.evaluate(ctx) as? Number)?.toInt() ?: 0
        val maxDigits = (maxFractionDigits?.evaluate(ctx) as? Number)?.toInt() ?: maxOf(minDigits, 3)

        val formatted = formatDecimal(value, minDigits, maxDigits)

        val currencyCode = currency?.evaluate(ctx) as? String
        val unitName = unit?.evaluate(ctx) as? String
        return when {
            currencyCode != null -> "$currencyCode$formatted"
            unitName != null -> "$formatted $unitName"
            else -> formatted
        }
    }

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
        /** Beyond this, a Double carries no meaningful precision and a Long would overflow. */
        private const val MAX_FRACTION_DIGITS = 15

        private fun formatDecimal(value: Double, minDigits: Int, maxDigits: Int): String {
            if (value.isNaN()) return "NaN"
            if (value.isInfinite()) return if (value > 0) "∞" else "-∞"

            val negative = value < 0
            val magnitude = abs(value)

            // Split rather than scaling the whole value: a maxDigits of 20 would overflow a Long.
            val wholePart = floor(magnitude)
            var intPart = wholePart.toLong().toString()

            val effectiveMax = maxDigits.coerceAtMost(MAX_FRACTION_DIGITS)
            var fracPart = if (effectiveMax > 0) {
                val scale = 10.0.pow(effectiveMax)
                var scaled = ((magnitude - wholePart) * scale).roundToLong()
                if (scaled >= scale.toLong()) {
                    // Rounding carried into the integer part.
                    intPart = (wholePart.toLong() + 1).toString()
                    scaled = 0
                }
                scaled.toString().padStart(effectiveMax, '0')
            } else {
                ""
            }

            // Pad out to minDigits; doubles carry no more precision than effectiveMax anyway.
            if (fracPart.length < minDigits) fracPart = fracPart.padEnd(minDigits, '0')
            while (fracPart.length > minDigits && fracPart.endsWith('0')) {
                fracPart = fracPart.dropLast(1)
            }

            if (intPart.isEmpty()) intPart = "0"
            val body = if (fracPart.isEmpty()) intPart else "$intPart.$fracPart"
            return if (negative && body.any { it in '1'..'9' }) "-$body" else body
        }

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
