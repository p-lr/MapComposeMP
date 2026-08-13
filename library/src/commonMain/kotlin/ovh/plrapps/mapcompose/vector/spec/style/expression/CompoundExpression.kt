package ovh.plrapps.mapcompose.vector.spec.style.expression

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.Coercion
import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.Assertion
import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.Collator as CollatorExpression
import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.Distance
import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.GlobalState
import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.Literal
import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.Var
import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.Within
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.Collator
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A fixed-arity or variadic parameter list. */
sealed class Signature {
    data class Fixed(val params: List<ExprType>) : Signature()
    data class Varargs(val type: ExprType) : Signature()
}

typealias Evaluate = (ctx: EvaluationContext, args: List<Expression>, key: String) -> Any?

/** One operator's type, signature(s) and evaluation function. */
sealed class Definition {
    abstract val type: ExprType
    abstract val overloads: List<Pair<Signature, Evaluate>>

    data class Simple(
        override val type: ExprType,
        val signature: Signature,
        val evaluate: Evaluate,
    ) : Definition() {
        override val overloads: List<Pair<Signature, Evaluate>> get() = listOf(signature to evaluate)
    }

    data class Overloaded(
        override val type: ExprType,
        override val overloads: List<Pair<Signature, Evaluate>>,
    ) : Definition()
}

/**
 * The table-driven operators.
 *
 * Ported from `maplibre-style-spec/src/expression/compound_expression.ts`. Operators whose parsing
 * is uniform (fixed or variadic argument list, all arguments parsed with a known expected type)
 * live in [definitions]; anything needing custom parse logic gets its own class under
 * `definitions/`.
 */
class CompoundExpression(
    val name: String,
    override val type: ExprType,
    private val evaluateFn: Evaluate,
    val args: List<Expression>,
    val key: String,
) : Expression {

    override fun evaluate(ctx: EvaluationContext): Any? = evaluateFn(ctx, args, key)

    override fun eachChild(fn: (Expression) -> Unit) = args.forEach(fn)

    override fun outputDefined(): Boolean = false

    companion object {
        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            val op = args[0] as String
            val definition = definitions[op]
                ?: return context.error(
                    "Unknown expression \"$op\". If you wanted a literal array, use [\"literal\", [...]].",
                    0,
                )

            val type = definition.type
            val availableOverloads = definition.overloads

            // Only overloads whose parameter count matches are candidates.
            val overloads = availableOverloads.filter { (signature, _) ->
                signature !is Signature.Fixed || signature.params.size == args.size - 1
            }

            var signatureContext: ParsingContext? = null

            for ((params, evaluate) in overloads) {
                // Use a fresh context for each attempted signature so that, if we eventually
                // succeed, we haven't polluted context.errors.
                val sc = ParsingContext(
                    registry = context.registry,
                    isConstantFunc = ::isExpressionConstant,
                    path = context.path,
                    expectedType = null,
                    scope = context.scope,
                )
                signatureContext = sc

                val parsedArgs = mutableListOf<Expression>()
                var argParseFailed = false
                for (i in 1 until args.size) {
                    val arg = args[i]
                    val expectedType = when (params) {
                        is Signature.Fixed -> params.params.getOrNull(i - 1)
                        is Signature.Varargs -> params.type
                    }
                    val parsed = sc.parse(arg, 1 + parsedArgs.size, expectedType)
                    if (parsed == null) {
                        argParseFailed = true
                        break
                    }
                    parsedArgs.add(parsed)
                }
                if (argParseFailed) continue

                if (params is Signature.Fixed && params.params.size != parsedArgs.size) {
                    sc.error("Expected ${params.params.size} arguments, but found ${parsedArgs.size} instead.")
                    continue
                }

                for (i in parsedArgs.indices) {
                    val expected = when (params) {
                        is Signature.Fixed -> params.params[i]
                        is Signature.Varargs -> params.type
                    }
                    sc.concat(i + 1).checkSubtype(expected, parsedArgs[i].type)
                }

                if (sc.errors.isEmpty()) {
                    return CompoundExpression(op, type, evaluate, parsedArgs, context.key)
                }
            }

            if (overloads.size == 1) {
                context.errors.addAll(signatureContext?.errors.orEmpty())
            } else {
                val expected = overloads.ifEmpty { availableOverloads }
                val signatures = expected.joinToString(" | ") { (params, _) -> stringifySignature(params) }

                val actualTypes = mutableListOf<String>()
                // For the error message, re-parse arguments without applying any coercions.
                for (i in 1 until args.size) {
                    val parsed = context.parse(args[i], 1 + actualTypes.size) ?: return null
                    actualTypes.add(typeToString(parsed.type))
                }
                context.error("Expected arguments of type $signatures, but found (${actualTypes.joinToString(", ")}) instead.")
            }

            return null
        }

        private fun stringifySignature(signature: Signature): String = when (signature) {
            is Signature.Fixed -> "(${signature.params.joinToString(", ") { typeToString(it) }})"
            is Signature.Varargs -> "(${typeToString(signature.type)}...)"
        }

        private fun fixed(vararg params: ExprType): Signature = Signature.Fixed(params.toList())
        private fun varargs(type: ExprType): Signature = Signature.Varargs(type)

        private fun simple(type: ExprType, signature: Signature, evaluate: Evaluate): Definition =
            Definition.Simple(type, signature, evaluate)

        private fun num(e: Expression, ctx: EvaluationContext): Double = (e.evaluate(ctx) as Number).toDouble()
        private fun str(e: Expression, ctx: EvaluationContext): String = e.evaluate(ctx) as String

        private fun rgba(ctx: EvaluationContext, args: List<Expression>, key: String): Any {
            val r = args[0].evaluate(ctx)
            val g = args[1].evaluate(ctx)
            val b = args[2].evaluate(ctx)
            val alpha = if (args.size > 3) args[3].evaluate(ctx) else 1.0
            val error = validateRGBA(r, g, b, alpha)
            if (error != null) throw RuntimeError(error, key)
            return Color(
                red = ((r as Number).toDouble() / 255.0).toFloat(),
                green = ((g as Number).toDouble() / 255.0).toFloat(),
                blue = ((b as Number).toDouble() / 255.0).toFloat(),
                alpha = (alpha as Number).toDouble().toFloat(),
            )
        }

        private fun hasKey(key: Any?, obj: Map<*, *>): Boolean = obj.containsKey(key)

        private fun getKey(key: Any?, obj: Map<*, *>): Any? = obj[key]

        private fun binarySearch(v: Any?, a: List<Any?>, from: Int, to: Int): Boolean {
            var i = from
            var j = to
            while (i <= j) {
                val m = (i + j) / 2
                val cmp = compareJsValues(a[m], v) ?: return false
                if (cmp == 0) return true
                if (cmp > 0) j = m - 1 else i = m + 1
            }
            return false
        }

        /** `Math.round` semantics: ties toward +Infinity, then upstream's away-from-zero fixup. */
        private fun jsRound(v: Double): Double = floor(v + 0.5)

        private fun roundAwayFromZero(v: Double): Double = if (v < 0) -jsRound(-v) else jsRound(v)

        /**
         * Comparison used by the legacy `filter-*` operators and by [binarySearch]: JavaScript's
         * relational operators only produce a meaningful result when both operands have the same
         * `typeof`, which upstream checks explicitly. Returns `null` for mismatched types.
         */
        private fun compareJsValues(a: Any?, b: Any?): Int? = when {
            a is Number && b is Number -> a.toDouble().compareTo(b.toDouble())
            a is String && b is String -> a.compareTo(b)
            a is Boolean && b is Boolean -> a.compareTo(b)
            else -> null
        }

        private val RELATIONAL_TESTS: List<Pair<String, (Int) -> Boolean>> = listOf(
            "<" to { c: Int -> c < 0 },
            ">" to { c: Int -> c > 0 },
            "<=" to { c: Int -> c <= 0 },
            ">=" to { c: Int -> c >= 0 },
        )

        private fun literalValue(e: Expression): Any? = (e as? Literal)?.value

        @Suppress("UNCHECKED_CAST")
        val definitions: Map<String, Definition> = buildMap {
            put("error", simple(ErrorType, fixed(StringType)) { ctx, args, key ->
                throw RuntimeError(args[0].evaluate(ctx) as String, key)
            })
            put("typeof", simple(StringType, fixed(ValueType)) { ctx, args, _ ->
                typeToString(typeOf(args[0].evaluate(ctx)))
            })
            put("to-rgba", simple(array(NumberType, 4), fixed(ColorType)) { ctx, args, _ ->
                val c = args[0].evaluate(ctx) as Color
                listOf(c.red * 255.0, c.green * 255.0, c.blue * 255.0, c.alpha.toDouble())
            })
            put("rgb", simple(ColorType, fixed(NumberType, NumberType, NumberType), ::rgba))
            put("rgba", simple(ColorType, fixed(NumberType, NumberType, NumberType, NumberType), ::rgba))
            put(
                "has", Definition.Overloaded(
                    BooleanType, listOf(
                        fixed(StringType) to { ctx: EvaluationContext, args: List<Expression>, _: String ->
                            hasKey(args[0].evaluate(ctx), ctx.properties())
                        },
                        fixed(StringType, ObjectType) to { ctx: EvaluationContext, args: List<Expression>, _: String ->
                            hasKey(args[0].evaluate(ctx), args[1].evaluate(ctx) as Map<*, *>)
                        },
                    )
                )
            )
            put(
                "get", Definition.Overloaded(
                    ValueType, listOf(
                        fixed(StringType) to { ctx: EvaluationContext, args: List<Expression>, _: String ->
                            getKey(args[0].evaluate(ctx), ctx.properties())
                        },
                        fixed(StringType, ObjectType) to { ctx: EvaluationContext, args: List<Expression>, _: String ->
                            getKey(args[0].evaluate(ctx), args[1].evaluate(ctx) as Map<*, *>)
                        },
                    )
                )
            )
            put("feature-state", simple(ValueType, fixed(StringType)) { ctx, args, _ ->
                getKey(args[0].evaluate(ctx), ctx.featureState ?: emptyMap<String, Any?>())
            })
            put("properties", simple(ObjectType, fixed()) { ctx, _, _ -> ctx.properties() })
            put("geometry-type", simple(StringType, fixed()) { ctx, _, _ -> ctx.geometryType() })
            put("id", simple(ValueType, fixed()) { ctx, _, _ -> ctx.id() })
            put("zoom", simple(NumberType, fixed()) { ctx, _, _ -> ctx.globals?.zoom ?: 0.0 })
            put("heatmap-density", simple(NumberType, fixed()) { ctx, _, _ -> ctx.globals?.heatmapDensity ?: 0.0 })
            put("elevation", simple(NumberType, fixed()) { ctx, _, _ -> ctx.globals?.elevation ?: 0.0 })
            put("line-progress", simple(NumberType, fixed()) { ctx, _, _ -> ctx.globals?.lineProgress ?: 0.0 })
            put("accumulated", simple(ValueType, fixed()) { ctx, _, _ -> ctx.globals?.accumulated })

            put("+", simple(NumberType, varargs(NumberType)) { ctx, args, _ ->
                var result = 0.0
                for (arg in args) result += num(arg, ctx)
                result
            })
            put("*", simple(NumberType, varargs(NumberType)) { ctx, args, _ ->
                var result = 1.0
                for (arg in args) result *= num(arg, ctx)
                result
            })
            put(
                "-", Definition.Overloaded(
                    NumberType, listOf(
                        fixed(NumberType, NumberType) to { ctx: EvaluationContext, args: List<Expression>, _: String ->
                            num(args[0], ctx) - num(args[1], ctx)
                        },
                        fixed(NumberType) to { ctx: EvaluationContext, args: List<Expression>, _: String ->
                            -num(args[0], ctx)
                        },
                    )
                )
            )
            put("/", simple(NumberType, fixed(NumberType, NumberType)) { ctx, args, _ ->
                num(args[0], ctx) / num(args[1], ctx)
            })
            put("%", simple(NumberType, fixed(NumberType, NumberType)) { ctx, args, _ ->
                num(args[0], ctx) % num(args[1], ctx)
            })
            put("ln2", simple(NumberType, fixed()) { _, _, _ -> ln(2.0) })
            put("pi", simple(NumberType, fixed()) { _, _, _ -> PI })
            put("e", simple(NumberType, fixed()) { _, _, _ -> E })
            put("^", simple(NumberType, fixed(NumberType, NumberType)) { ctx, args, _ ->
                num(args[0], ctx).pow(num(args[1], ctx))
            })
            put("sqrt", simple(NumberType, fixed(NumberType)) { ctx, args, _ -> sqrt(num(args[0], ctx)) })
            put("log10", simple(NumberType, fixed(NumberType)) { ctx, args, _ -> ln(num(args[0], ctx)) / ln(10.0) })
            put("ln", simple(NumberType, fixed(NumberType)) { ctx, args, _ -> ln(num(args[0], ctx)) })
            put("log2", simple(NumberType, fixed(NumberType)) { ctx, args, _ -> ln(num(args[0], ctx)) / ln(2.0) })
            put("sin", simple(NumberType, fixed(NumberType)) { ctx, args, _ -> sin(num(args[0], ctx)) })
            put("cos", simple(NumberType, fixed(NumberType)) { ctx, args, _ -> cos(num(args[0], ctx)) })
            put("tan", simple(NumberType, fixed(NumberType)) { ctx, args, _ -> tan(num(args[0], ctx)) })
            put("asin", simple(NumberType, fixed(NumberType)) { ctx, args, _ -> asin(num(args[0], ctx)) })
            put("acos", simple(NumberType, fixed(NumberType)) { ctx, args, _ -> acos(num(args[0], ctx)) })
            put("atan", simple(NumberType, fixed(NumberType)) { ctx, args, _ -> atan(num(args[0], ctx)) })
            put("min", simple(NumberType, varargs(NumberType)) { ctx, args, _ ->
                // Math.min() with no arguments is +Infinity in JavaScript.
                args.fold(Double.POSITIVE_INFINITY) { acc, arg -> minOf(acc, num(arg, ctx)) }
            })
            put("max", simple(NumberType, varargs(NumberType)) { ctx, args, _ ->
                args.fold(Double.NEGATIVE_INFINITY) { acc, arg -> maxOf(acc, num(arg, ctx)) }
            })
            put("abs", simple(NumberType, fixed(NumberType)) { ctx, args, _ -> abs(num(args[0], ctx)) })
            put("round", simple(NumberType, fixed(NumberType)) { ctx, args, _ ->
                roundAwayFromZero(num(args[0], ctx))
            })
            put("floor", simple(NumberType, fixed(NumberType)) { ctx, args, _ -> floor(num(args[0], ctx)) })
            put("ceil", simple(NumberType, fixed(NumberType)) { ctx, args, _ -> ceil(num(args[0], ctx)) })

            // Internal operators produced by the legacy-filter converter. They are never written
            // by style authors; see spec/style/filter/FeatureFilter.kt.
            put("filter-==", simple(BooleanType, fixed(StringType, ValueType)) { ctx, args, _ ->
                jsStrictEqual(ctx.properties()[literalValue(args[0])], literalValue(args[1]))
            })
            put("filter-id-==", simple(BooleanType, fixed(ValueType)) { ctx, args, _ ->
                jsStrictEqual(ctx.id(), literalValue(args[0]))
            })
            put("filter-type-==", simple(BooleanType, fixed(StringType)) { ctx, args, _ ->
                ctx.geometryType() == literalValue(args[0])
            })
            for ((op, test) in RELATIONAL_TESTS) {
                put("filter-$op", simple(BooleanType, fixed(StringType, ValueType)) { ctx, args, _ ->
                    val cmp = compareJsValues(ctx.properties()[literalValue(args[0])], literalValue(args[1]))
                    cmp != null && test(cmp)
                })
                put("filter-id-$op", simple(BooleanType, fixed(ValueType)) { ctx, args, _ ->
                    val cmp = compareJsValues(ctx.id(), literalValue(args[0]))
                    cmp != null && test(cmp)
                })
            }
            put("filter-has", simple(BooleanType, fixed(ValueType)) { ctx, args, _ ->
                hasKey(literalValue(args[0]), ctx.properties())
            })
            put("filter-has-id", simple(BooleanType, fixed()) { ctx, _, _ -> ctx.id() != null })
            put("filter-type-in", simple(BooleanType, fixed(array(StringType))) { ctx, args, _ ->
                (literalValue(args[0]) as List<Any?>).contains(ctx.geometryType())
            })
            put("filter-id-in", simple(BooleanType, fixed(array(ValueType))) { ctx, args, _ ->
                (literalValue(args[0]) as List<Any?>).any { jsStrictEqual(it, ctx.id()) }
            })
            put("filter-in-small", simple(BooleanType, fixed(StringType, array(ValueType))) { ctx, args, _ ->
                val v = literalValue(args[1]) as List<Any?>
                val target = ctx.properties()[literalValue(args[0])]
                v.any { jsStrictEqual(it, target) }
            })
            put("filter-in-large", simple(BooleanType, fixed(StringType, array(ValueType))) { ctx, args, _ ->
                val v = literalValue(args[1]) as List<Any?>
                binarySearch(ctx.properties()[literalValue(args[0])], v, 0, v.size - 1)
            })

            put(
                "all", Definition.Overloaded(
                    BooleanType, listOf(
                        fixed(BooleanType, BooleanType) to { ctx: EvaluationContext, args: List<Expression>, _: String ->
                            (args[0].evaluate(ctx) as Boolean) && (args[1].evaluate(ctx) as Boolean)
                        },
                        varargs(BooleanType) to { ctx: EvaluationContext, args: List<Expression>, _: String ->
                            args.all { it.evaluate(ctx) as Boolean }
                        },
                    )
                )
            )
            put(
                "any", Definition.Overloaded(
                    BooleanType, listOf(
                        fixed(BooleanType, BooleanType) to { ctx: EvaluationContext, args: List<Expression>, _: String ->
                            (args[0].evaluate(ctx) as Boolean) || (args[1].evaluate(ctx) as Boolean)
                        },
                        varargs(BooleanType) to { ctx: EvaluationContext, args: List<Expression>, _: String ->
                            args.any { it.evaluate(ctx) as Boolean }
                        },
                    )
                )
            )
            put("!", simple(BooleanType, fixed(BooleanType)) { ctx, args, _ ->
                !(args[0].evaluate(ctx) as Boolean)
            })
            put("is-supported-script", simple(BooleanType, fixed(StringType)) { ctx, args, _ ->
                ctx.globals?.isSupportedScript?.invoke(str(args[0], ctx)) ?: true
            })
            put("upcase", simple(StringType, fixed(StringType)) { ctx, args, _ -> str(args[0], ctx).uppercase() })
            put("downcase", simple(StringType, fixed(StringType)) { ctx, args, _ -> str(args[0], ctx).lowercase() })
            put("concat", simple(StringType, varargs(ValueType)) { ctx, args, _ ->
                args.joinToString(separator = "") { valueToString(it.evaluate(ctx)) }
            })
            put("split", simple(array(StringType), fixed(StringType, StringType)) { ctx, args, _ ->
                val s = str(args[0], ctx)
                val delim = str(args[1], ctx)
                // String.prototype.split('') splits into characters.
                if (delim.isEmpty()) s.map { it.toString() } else s.split(delim)
            })
            put("join", simple(StringType, fixed(array(StringType), StringType)) { ctx, args, _ ->
                (args[0].evaluate(ctx) as List<Any?>).joinToString(str(args[1], ctx)) { valueToString(it) }
            })
            put("resolved-locale", simple(StringType, fixed(CollatorType)) { ctx, args, _ ->
                (args[0].evaluate(ctx) as Collator).resolvedLocale()
            })
        }
    }
}

/**
 * Whether an expression's value is independent of the feature being rendered.
 *
 * Ported from `isFeatureConstant` in `compound_expression.ts`.
 */
fun isFeatureConstant(e: Expression): Boolean {
    if (e is CompoundExpression) {
        if (e.name == "get" && e.args.size == 1) return false
        if (e.name == "feature-state") return false
        if (e.name == "has" && e.args.size == 1) return false
        if (e.name == "properties" || e.name == "geometry-type" || e.name == "id") return false
        if (e.name.startsWith("filter-")) return false
    }
    if (e is Within || e is Distance) return false

    var result = true
    e.eachChild { arg -> if (result && !isFeatureConstant(arg)) result = false }
    return result
}

/** Ported from `isStateConstant`. */
fun isStateConstant(e: Expression): Boolean {
    if (e is CompoundExpression && e.name == "feature-state") return false
    var result = true
    e.eachChild { arg -> if (result && !isStateConstant(arg)) result = false }
    return result
}

/** Ported from `isGlobalPropertyConstant`. */
fun isGlobalPropertyConstant(e: Expression, properties: List<String>): Boolean {
    if (e is CompoundExpression && e.name in properties) return false
    var result = true
    e.eachChild { arg -> if (result && !isGlobalPropertyConstant(arg, properties)) result = false }
    return result
}

private val GLOBAL_PROPERTY_NAMES = listOf(
    "zoom", "heatmap-density", "elevation", "line-progress", "accumulated", "is-supported-script",
)

/**
 * Whether an expression can be evaluated once at parse time and replaced with its value.
 *
 * Ported from `isExpressionConstant`.
 */
fun isExpressionConstant(expression: Expression): Boolean {
    when (expression) {
        is Var -> return isExpressionConstant(expression.boundExpression)
        is CompoundExpression -> if (expression.name == "error") return false
        // Although a Collator with fixed arguments generally shouldn't change between executions,
        // we can't fold it because the result depends on the environment.
        is CollatorExpression -> return false
        is Within -> return false
        is Distance -> return false
        is GlobalState -> return false
        else -> Unit
    }

    val isTypeAnnotation = expression is Coercion || expression is Assertion

    var childrenConstant = true
    expression.eachChild { child ->
        // We can almost assume that if an expression's children are constant they would already
        // have been folded into Literals when parsed. Type annotations are the exception, because
        // they might have been inferred and added after a child was parsed.
        childrenConstant = if (isTypeAnnotation) {
            childrenConstant && isExpressionConstant(child)
        } else {
            childrenConstant && child is Literal
        }
    }
    if (!childrenConstant) return false

    return isFeatureConstant(expression) && isGlobalPropertyConstant(expression, GLOBAL_PROPERTY_NAMES)
}
