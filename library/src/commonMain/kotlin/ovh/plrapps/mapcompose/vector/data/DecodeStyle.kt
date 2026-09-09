package ovh.plrapps.mapcompose.vector.data

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.jsonObject
import ovh.plrapps.mapcompose.vector.spec.style.MapLibreStyle
import ovh.plrapps.mapcompose.vector.spec.style.StateSpec
import ovh.plrapps.mapcompose.vector.spec.style.expression.jsonToValue
import ovh.plrapps.mapcompose.vector.spec.style.utils.StyleGlobalState

/**
 * Decodes a style, with its `state` defaults in scope for every expression it compiles.
 *
 * Every expression and every layer filter in a style is compiled *during* this decode, by
 * `ExpressionOrValueSerializer` and `FeatureFilterSerializer`, and each bakes the global state into
 * the `StyleExpression` it produces. So the defaults have to be known before the first layer is
 * read -- and JSON guarantees nothing about key order, so `state` may well come after `layers`.
 * Hence the tree: it is parsed once, `state` is lifted off the root, and the decode runs from the
 * element rather than from the text. The cost is one `JsonElement` graph per style load, against
 * re-compiling every property afterwards, which cannot be done without reflection.
 *
 * See [StyleGlobalState] for why a holder rather than an argument.
 */
fun decodeStyle(style: String): MapLibreStyle {
    val root = json.parseToJsonElement(style).jsonObject
    val state = root["state"]?.let {
        json.decodeFromJsonElement(MapSerializer(String.serializer(), StateSpec.serializer()), it)
    }
    StyleGlobalState.defaults = globalStateDefaults(state).takeIf { it.isNotEmpty() }
    return try {
        json.decodeFromJsonElement(MapLibreStyle.serializer(), root)
    } finally {
        StyleGlobalState.defaults = null
    }
}

/** The style's `state` defaults, in the shape `["global-state", k]` reads them at. */
fun MapLibreStyle.globalStateDefaults(): Map<String, Any?> = globalStateDefaults(state)

/**
 * Flattens `"state": {"k": {"default": v}}` into `{"k": v}`.
 *
 * Ported from `getGlobalStateDefaults` (`maplibre-style-spec/src/util/get_global_state_defaults.ts`).
 * An entry with no `default` still contributes its key, with a `null` value -- upstream reads
 * `value.default` unconditionally, and a declared-but-undefined key is not an undeclared one.
 */
private fun globalStateDefaults(state: Map<String, StateSpec>?): Map<String, Any?> =
    state?.entries?.associate { (key, spec) -> key to spec.default?.let { jsonToValue(it) } }
        ?: emptyMap()
