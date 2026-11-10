package ovh.plrapps.mapcompose.vector.spec.style.utils

/**
 * Carries a style's `state` defaults into the expression compilers while it is being decoded.
 *
 * `["global-state", "k"]` reads `GlobalProperties.globalState`, and the map it reads is baked into
 * each `StyleExpression` at *compile* time -- upstream's
 * `createExpression(value, key, spec, globalState)`, called from `Style._load` with
 * `getGlobalStateDefaults(stylesheet.state)`. Compilation happens inside `Json.decodeFromString`
 * here, and a kotlinx serializer is handed no per-decode context, so the map has to reach
 * `ExpressionOrValueSerializer` and `FeatureFilterSerializer` some other way. This is that way, and
 * it is the same shape as [StyleDiagnostics], which exists because the same seam gives a serializer
 * no channel to report a non-fatal parse error either.
 *
 * `decodeStyle` sets [defaults] before the decode and clears it afterwards. Like [StyleDiagnostics]
 * this assumes one style is decoded at a time, which one `decodeFromString` call is.
 */
object StyleGlobalState {
    /** The style's flattened `state` defaults, or `null` outside a decode / for a style with none. */
    var defaults: Map<String, Any?>? = null
}
