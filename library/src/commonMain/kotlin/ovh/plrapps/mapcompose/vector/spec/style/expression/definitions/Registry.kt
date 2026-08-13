package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import ovh.plrapps.mapcompose.vector.spec.style.expression.CompoundExpression
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExpressionRegistry

/**
 * Operator name to parser.
 *
 * Ported from `maplibre-style-spec/src/expression/definitions/index.ts`. Every operator not listed
 * here falls through to [CompoundExpression.parse], which looks it up in the table-driven
 * definitions and reports `Unknown expression "…"` if it isn't there either.
 */
val expressions: ExpressionRegistry = buildMap {
    // Special forms with their own parse logic.
    put("==", Comparison.parser("=="))
    put("!=", Comparison.parser("!="))
    put(">", Comparison.parser(">"))
    put("<", Comparison.parser("<"))
    put(">=", Comparison.parser(">="))
    put("<=", Comparison.parser("<="))
    put("array", Assertion::parse)
    put("at", At::parse)
    put("boolean", Assertion::parse)
    put("case", Case::parse)
    put("coalesce", Coalesce::parse)
    put("collator", Collator::parse)
    put("distance", Distance::parse)
    put("format", Format::parse)
    put("global-state", GlobalState::parse)
    put("image", Image::parse)
    put("in", In::parse)
    put("index-of", IndexOf::parse)
    put("interpolate", Interpolate.parser("interpolate"))
    put("interpolate-hcl", Interpolate.parser("interpolate-hcl"))
    put("interpolate-lab", Interpolate.parser("interpolate-lab"))
    put("length", Length::parse)
    put("let", Let::parse)
    put("literal", Literal::parse)
    put("match", Match::parse)
    put("number", Assertion::parse)
    put("number-format", NumberFormat::parse)
    put("object", Assertion::parse)
    put("semiliteral", Semiliteral::parse)
    put("slice", Slice::parse)
    put("step", Step::parse)
    put("string", Assertion::parse)
    put("to-boolean", Coercion::parse)
    put("to-color", Coercion::parse)
    put("to-number", Coercion::parse)
    put("to-string", Coercion::parse)
    put("var", Var::parse)
    put("within", Within::parse)

    // Everything else is table-driven.
    for (name in CompoundExpression.definitions.keys) {
        put(name, CompoundExpression::parse)
    }
}
