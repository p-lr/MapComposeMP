package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import ovh.plrapps.mapcompose.vector.spec.style.expression.BooleanType
import ovh.plrapps.mapcompose.vector.spec.style.expression.CollatorType
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.NullType
import ovh.plrapps.mapcompose.vector.spec.style.expression.NumberType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.RuntimeError
import ovh.plrapps.mapcompose.vector.spec.style.expression.StringType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ValueType
import ovh.plrapps.mapcompose.vector.spec.style.expression.jsStrictEqual
import ovh.plrapps.mapcompose.vector.spec.style.expression.typeOf
import ovh.plrapps.mapcompose.vector.spec.style.expression.typeToString
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.Collator as CollatorValue

/**
 * Special form for the comparison operators, implementing the signatures:
 * - `(T, T, ?Collator) => boolean`
 * - `(T, value, ?Collator) => boolean`
 * - `(value, T, ?Collator) => boolean`
 *
 * For inequalities `T` must be `value`, `string` or `number`. For `==`/`!=` it may also be
 * `boolean` or `null`.
 *
 * Equality semantics are JavaScript's strict equality: when the argument types don't match, `==`
 * evaluates to false and `!=` to true. When types don't match in an *ordering* comparison, a
 * runtime error is thrown.
 *
 * Ported from `maplibre-style-spec/src/expression/definitions/comparison.ts`.
 */
class Comparison(
    val op: String,
    val lhs: Expression,
    val rhs: Expression,
    val key: String,
    val collator: Expression? = null,
) : Expression {

    override val type: ExprType = BooleanType

    private val isOrderComparison: Boolean = op != "==" && op != "!="

    private val hasUntypedArgument: Boolean = lhs.type == ValueType || rhs.type == ValueType

    override fun evaluate(ctx: EvaluationContext): Any {
        val l = lhs.evaluate(ctx)
        val r = rhs.evaluate(ctx)

        if (isOrderComparison && hasUntypedArgument) {
            val lt = typeOf(l)
            val rt = typeOf(r)
            if (lt.kind != rt.kind || !(lt == StringType || lt == NumberType)) {
                throw RuntimeError(
                    "Expected arguments for \"$op\" to be (string, string) or (number, number), " +
                            "but found (${lt.kind}, ${rt.kind}) instead.",
                    key,
                )
            }
        }

        if (collator != null && !isOrderComparison && hasUntypedArgument) {
            if (typeOf(l) != StringType || typeOf(r) != StringType) {
                return compareBasic(l, r)
            }
        }

        return if (collator != null) {
            compareWithCollator(l, r, collator.evaluate(ctx) as CollatorValue)
        } else {
            compareBasic(l, r)
        }
    }

    private fun compareBasic(a: Any?, b: Any?): Boolean = when (op) {
        "==" -> jsStrictEqual(a, b)
        "!=" -> !jsStrictEqual(a, b)
        else -> {
            val cmp = orderCompare(a, b)
            when (op) {
                "<" -> cmp < 0
                ">" -> cmp > 0
                "<=" -> cmp <= 0
                else -> cmp >= 0
            }
        }
    }

    private fun compareWithCollator(a: Any?, b: Any?, c: CollatorValue): Boolean {
        val cmp = c.compare(a as String, b as String)
        return when (op) {
            "==" -> cmp == 0
            "!=" -> cmp != 0
            "<" -> cmp < 0
            ">" -> cmp > 0
            "<=" -> cmp <= 0
            else -> cmp >= 0
        }
    }

    private fun orderCompare(a: Any?, b: Any?): Int = when {
        a is Number && b is Number -> a.toDouble().compareTo(b.toDouble())
        a is String && b is String -> a.compareTo(b)
        else -> throw RuntimeError(
            "Expected arguments for \"$op\" to be (string, string) or (number, number), " +
                    "but found (${typeOf(a).kind}, ${typeOf(b).kind}) instead.",
            key,
        )
    }

    override fun eachChild(fn: (Expression) -> Unit) {
        fn(lhs)
        fn(rhs)
        collator?.let(fn)
    }

    override fun outputDefined(): Boolean = true

    companion object {
        private fun isComparableType(op: String, type: ExprType): Boolean = if (op == "==" || op == "!=") {
            type == BooleanType || type == StringType || type == NumberType ||
                    type == NullType || type == ValueType
        } else {
            type == StringType || type == NumberType || type == ValueType
        }

        fun parser(op: String): (List<Any?>, ParsingContext) -> Expression? = { args, context ->
            parse(op, args, context)
        }

        private fun parse(op: String, args: List<Any?>, context: ParsingContext): Expression? {
            val isOrderComparison = op != "==" && op != "!="

            if (args.size != 3 && args.size != 4) {
                return context.error("Expected two or three arguments.")
            }

            var lhs = context.parse(args[1], 1, ValueType) ?: return null
            if (!isComparableType(op, lhs.type)) {
                return context.concat(1)
                    .error("\"$op\" comparisons are not supported for type '${typeToString(lhs.type)}'.")
            }
            var rhs = context.parse(args[2], 2, ValueType) ?: return null
            if (!isComparableType(op, rhs.type)) {
                return context.concat(2)
                    .error("\"$op\" comparisons are not supported for type '${typeToString(rhs.type)}'.")
            }

            if (lhs.type.kind != rhs.type.kind && lhs.type != ValueType && rhs.type != ValueType) {
                return context.error(
                    "Cannot compare types '${typeToString(lhs.type)}' and '${typeToString(rhs.type)}'."
                )
            }

            if (isOrderComparison) {
                // Typing rules specific to the less/greater-than operators.
                if (lhs.type == ValueType && rhs.type != ValueType) {
                    lhs = Assertion(rhs.type, listOf(lhs), context.key)
                } else if (lhs.type != ValueType && rhs.type == ValueType) {
                    rhs = Assertion(lhs.type, listOf(rhs), context.key)
                }
            }

            var collator: Expression? = null
            if (args.size == 4) {
                if (lhs.type != StringType && rhs.type != StringType &&
                    lhs.type != ValueType && rhs.type != ValueType
                ) {
                    return context.error("Cannot use collator to compare non-string types.")
                }
                collator = context.parse(args[3], 3, CollatorType) ?: return null
            }

            return Comparison(op, lhs, rhs, context.key, collator)
        }
    }
}
