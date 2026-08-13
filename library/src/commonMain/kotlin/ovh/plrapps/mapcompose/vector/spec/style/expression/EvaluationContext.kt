package ovh.plrapps.mapcompose.vector.spec.style.expression

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.FormattedSection
import ovh.plrapps.mapcompose.vector.spec.style.utils.ColorParser

/** A point in MVT tile-local coordinates (0..extent). */
data class Point2D(val x: Double, val y: Double)

/** The `(z, x, y)` address of the tile a feature came from. Needed by `within` and `distance`. */
data class CanonicalTileId(val z: Int, val x: Int, val y: Int)

/**
 * The feature an expression is evaluated against.
 *
 * Ported from the `Feature` type in `maplibre-style-spec/src/expression/index.ts`.
 *
 * [properties] values must already be normalized to engine values — see [normalizeNumbers].
 * [geometry] is in MVT tile-local coordinates and is a lambda so that decoding is skipped entirely
 * unless an expression actually asks for it (see `geometryNeeded` in the filter engine).
 */
class EvalFeature(
    val type: String,
    val id: Any? = null,
    val properties: Map<String, Any?> = emptyMap(),
    private val geometryProvider: (() -> List<List<Point2D>>)? = null,
) {
    val geometry: List<List<Point2D>> by lazy { geometryProvider?.invoke() ?: emptyList() }

    companion object {
        val GEOMETRY_TYPES = listOf("Unknown", "Point", "LineString", "Polygon")
    }
}

typealias FeatureState = Map<String, Any?>

/**
 * Camera / render-pass state. Ported from `GlobalProperties`.
 *
 * [isSupportedScript] is a hook so the renderer can answer for the fonts it actually has; the
 * default accepts everything, matching MapLibre's behaviour when no font stack is configured.
 */
data class GlobalProperties(
    val zoom: Double,
    val heatmapDensity: Double? = null,
    val elevation: Double? = null,
    val lineProgress: Double? = null,
    val accumulated: Any? = null,
    val globalState: Map<String, Any?>? = null,
    val isSupportedScript: ((String) -> Boolean)? = null,
)

/**
 * Mutable evaluation state, reused across evaluations to avoid per-feature allocation — exactly as
 * upstream does (`StyleExpression._evaluator`).
 *
 * Ported from `maplibre-style-spec/src/expression/evaluation_context.ts`.
 */
class EvaluationContext {
    var globals: GlobalProperties? = null
    var feature: EvalFeature? = null
    var featureState: FeatureState? = null
    var formattedSection: FormattedSection? = null
    var availableImages: List<String>? = null
    var canonical: CanonicalTileId? = null

    private val parseColorCache = mutableMapOf<String, Color?>()

    fun id(): Any? = feature?.id

    fun geometryType(): String? = feature?.type

    fun geometry(): List<List<Point2D>> = feature?.geometry ?: emptyList()

    fun canonicalID(): CanonicalTileId? = canonical

    fun properties(): Map<String, Any?> = feature?.properties ?: emptyMap()

    fun parseColor(input: String): Color? = parseColorCache.getOrPut(input) {
        ColorParser.parseColorStringOrNull(input)
    }
}
