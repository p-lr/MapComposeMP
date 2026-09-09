package ovh.plrapps.mapcompose.vector.spec.style.props

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.style.expression.StringType
import ovh.plrapps.mapcompose.vector.spec.style.expression.StylePropertySpec
import ovh.plrapps.mapcompose.vector.spec.style.serializers.ExpressionOrValueSerializer

/**
 * `layout.visibility`, which is an expression like any other property.
 *
 * Ported from `maplibre-style-spec/src/expression/visibility.ts`, whose `visibilitySpec` is
 * transcribed below. The property exists to be driven by global state and by nothing else: the
 * spec's `expression.parameters` is `["global-state"]` alone and its `property-type` is
 * `data-constant`. Modelled as a plain
 * `String?` -- which it was -- an expression-valued `visibility` decoded as the *text* of its JSON,
 * which is not `"none"`, so the layer drew unconditionally.
 *
 * [ExpressionOrValueSerializer] derives a property's spec from its Kotlin serializer, and a
 * `KSerializer<String>` says only "string", so the spec is handed over whole. The two
 * `supports*Expression` flags are deliberately left at their permissive defaults: upstream compiles
 * `visibility` with plain `createExpression`, which enforces neither, so a `["zoom"]` or a
 * `["get", ...]` in one is not a *compile* error there -- it evaluates against empty globals and no
 * feature and yields the default. The same thing happens here, either as a diagnostic (the zoom-curve
 * rule in `createPropertyExpression` rejects a bare `["zoom"]`) or as a runtime warning.
 */
val visibilitySpec: StylePropertySpec = StylePropertySpec(
    expectedType = StringType,
    defaultValue = StyleSpecDefaults.VISIBILITY,
    enumValues = setOf(StyleSpecDefaults.VISIBILITY, StyleSpecDefaults.VISIBILITY_NONE),
    supportsInterpolation = false,
)

object VisibilitySerializer :
    KSerializer<ExpressionOrValue<String>> by ExpressionOrValueSerializer(
        String.serializer(),
        visibilitySpec,
    )

/**
 * The spec default, shared by every layout class.
 *
 * A *value* rather than `null`, because the shared `Json` has `encodeDefaults = true`: a null
 * default would make every re-serialized layer grow a `"visibility": null`.
 */
val VISIBILITY_DEFAULT: ExpressionOrValue<String> =
    ExpressionOrValue.Value(StyleSpecDefaults.VISIBILITY, source = "\"${StyleSpecDefaults.VISIBILITY}\"")
