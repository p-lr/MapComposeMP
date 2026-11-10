package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.vector.spec.style.expression.ColorType
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.FormattedType
import ovh.plrapps.mapcompose.vector.spec.style.expression.NullType
import ovh.plrapps.mapcompose.vector.spec.style.expression.NumberType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ResolvedImageType
import ovh.plrapps.mapcompose.vector.spec.style.expression.StringType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ValueType
import ovh.plrapps.mapcompose.vector.spec.style.expression.array
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.Formatted
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.FormattedSection
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.ResolvedImage
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.VerticalAlign
import ovh.plrapps.mapcompose.vector.spec.style.expression.typeOf
import ovh.plrapps.mapcompose.vector.spec.style.expression.valueToString

/** Ported from `maplibre-style-spec/src/expression/definitions/format.ts`. */
class Format(val sections: List<Section>) : Expression {

    /**
     * A section's content may be an `image` expression or anything coercible to a string; the
     * options are only allowed on the token *after* a content token.
     */
    class Section(
        val content: Expression,
        var scale: Expression? = null,
        var font: Expression? = null,
        var textColor: Expression? = null,
        var verticalAlign: Expression? = null,
    )

    override val type: ExprType = FormattedType

    override fun evaluate(ctx: EvaluationContext): Any = Formatted(
        sections.map { section ->
            val evaluatedContent = section.content.evaluate(ctx)
            if (typeOf(evaluatedContent) == ResolvedImageType) {
                FormattedSection(
                    text = "",
                    image = evaluatedContent as ResolvedImage,
                    verticalAlign = VerticalAlign.fromStringOrNull(
                        section.verticalAlign?.evaluate(ctx) as? String
                    ),
                )
            } else {
                FormattedSection(
                    text = valueToString(evaluatedContent),
                    scale = (section.scale?.evaluate(ctx) as? Number)?.toDouble(),
                    fontStack = (section.font?.evaluate(ctx) as? List<*>)?.joinToString(","),
                    textColor = section.textColor?.evaluate(ctx) as? Color,
                    verticalAlign = VerticalAlign.fromStringOrNull(
                        section.verticalAlign?.evaluate(ctx) as? String
                    ),
                )
            }
        }
    )

    override fun eachChild(fn: (Expression) -> Unit) {
        for (section in sections) {
            fn(section.content)
            section.scale?.let(fn)
            section.font?.let(fn)
            section.textColor?.let(fn)
            section.verticalAlign?.let(fn)
        }
    }

    // Technically the combinatoric set of all children.
    override fun outputDefined(): Boolean = false

    companion object {
        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            if (args.size < 2) return context.error("Expected at least one argument.")

            val firstArg = args[1]
            if (firstArg is Map<*, *>) {
                return context.error("First argument must be an image or text section.")
            }

            val sections = mutableListOf<Section>()
            var nextTokenMayBeObject = false
            for (i in 1 until args.size) {
                val arg = args[i]

                if (nextTokenMayBeObject && arg is Map<*, *>) {
                    nextTokenMayBeObject = false

                    var scale: Expression? = null
                    if (arg["font-scale"] != null) {
                        scale = context.parse(arg["font-scale"], 1, NumberType) ?: return null
                    }

                    var font: Expression? = null
                    if (arg["text-font"] != null) {
                        font = context.parse(arg["text-font"], 1, array(StringType)) ?: return null
                    }

                    var textColor: Expression? = null
                    if (arg["text-color"] != null) {
                        textColor = context.parse(arg["text-color"], 1, ColorType) ?: return null
                    }

                    var verticalAlign: Expression? = null
                    val va = arg["vertical-align"]
                    if (va != null) {
                        if (va is String && VerticalAlign.fromStringOrNull(va) == null) {
                            return context.error(
                                "'vertical-align' must be one of: 'bottom', 'center', 'top' but " +
                                        "found '$va' instead."
                            )
                        }
                        verticalAlign = context.parse(va, 1, StringType) ?: return null
                    }

                    val lastExpression = sections.last()
                    lastExpression.scale = scale
                    lastExpression.font = font
                    lastExpression.textColor = textColor
                    lastExpression.verticalAlign = verticalAlign
                } else {
                    val content = context.parse(args[i], 1, ValueType) ?: return null

                    val kind = content.type
                    if (kind != StringType && kind != ValueType && kind != NullType && kind != ResolvedImageType) {
                        return context.error("Formatted text type must be 'string', 'value', 'image' or 'null'.")
                    }

                    nextTokenMayBeObject = true
                    sections.add(Section(content))
                }
            }

            return Format(sections)
        }
    }
}
