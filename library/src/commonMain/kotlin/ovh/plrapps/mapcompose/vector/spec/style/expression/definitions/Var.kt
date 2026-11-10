package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext

/**
 * Ported from `maplibre-style-spec/src/expression/definitions/var.ts`.
 *
 * Resolution happens at *parse* time: the variable name is looked up in the enclosing [Scope] and
 * the bound expression is referenced directly, so evaluation costs nothing extra.
 */
class Var(val name: String, val boundExpression: Expression) : Expression {

    override val type: ExprType = boundExpression.type

    override fun evaluate(ctx: EvaluationContext): Any? = boundExpression.evaluate(ctx)

    override fun eachChild(fn: (Expression) -> Unit) = Unit

    override fun outputDefined(): Boolean = false

    companion object {
        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            if (args.size != 2 || args[1] !is String) {
                return context.error("'var' expression requires exactly one string literal argument.")
            }

            val name = args[1] as String
            if (!context.scope.has(name)) {
                return context.error(
                    "Unknown variable \"$name\". Make sure \"$name\" has been bound in an enclosing " +
                            "\"let\" expression before using it.",
                    1,
                )
            }

            return Var(name, context.scope.get(name))
        }
    }
}
