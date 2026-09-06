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
 * The state one evaluation reads: the camera globals, the feature, and the tile it came from.
 *
 * Ported from `maplibre-style-spec/src/expression/evaluation_context.ts`, with one deliberate
 * divergence. Upstream's context is a *mutable* object that `StyleExpression` reuses across
 * evaluations (`StyleExpression._evaluator`) to avoid per-feature allocation, which is safe there
 * because JS is single-threaded. Here a style's parsed expressions are one object graph shared by
 * the whole tile-worker pool (`core/TileCollector.kt`) and by the symbol layout pass, so a reused
 * context is a data race: one worker overwrites [feature] while another is mid-tree-walk, that walk
 * reads the wrong properties, the resulting type error is caught by [StyleExpression.evaluate], and
 * the property falls back to its spec default -- which is how a white road rendered black.
 *
 * So the context is immutable and built per evaluation. Nothing in the expression definitions ever
 * wrote to it -- every access is a read -- so the only cost is one six-field allocation, next to the
 * [GlobalProperties] the caller already allocates for the same call.
 */
class EvaluationContext(
    val globals: GlobalProperties? = null,
    val feature: EvalFeature? = null,
    val featureState: FeatureState? = null,
    val formattedSection: FormattedSection? = null,
    val availableImages: List<String>? = null,
    val canonical: CanonicalTileId? = null,
) {
    fun id(): Any? = feature?.id

    fun geometryType(): String? = feature?.type

    fun geometry(): List<List<Point2D>> = feature?.geometry ?: emptyList()

    fun canonicalID(): CanonicalTileId? = canonical

    fun properties(): Map<String, Any?> = feature?.properties ?: emptyMap()

    fun parseColor(input: String): Color? = RuntimeColorCache.parse(input)
}

/**
 * Memoises `to-color` applied to a value that is not a literal.
 *
 * [ParsingContext] constant-folds every all-literal subtree, so the only colour strings parsed at
 * run time come from feature data -- rare, but then repeated once per feature. The cache used to sit
 * on [EvaluationContext], which is now per-evaluation, and a plain map here would be the same
 * unguarded `LinkedHashMap` shared across the worker pool that the context itself was. Copy-on-write
 * instead: a lost update costs one re-parse, where a raced map costs correctness. The bound exists
 * because the key space is feature data.
 */
internal object RuntimeColorCache {
    private const val MAX_SIZE = 256

    private var entries: Map<String, Color?> = emptyMap()

    fun parse(input: String): Color? {
        val snapshot = entries
        if (snapshot.containsKey(input)) return snapshot[input]

        val parsed = ColorParser.parseColorStringOrNull(input)
        entries = if (snapshot.size >= MAX_SIZE) {
            mapOf(input to parsed)
        } else {
            snapshot + (input to parsed)
        }
        return parsed
    }
}
