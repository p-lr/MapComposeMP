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
 * [geometry] is in the engine's own tile space (`EXTENT = 8192`, not the MVT layer's extent) and is
 * behind a lambda so that decoding is skipped entirely unless an expression actually asks for it.
 * Upstream gates the same work with `FeatureFilter.needGeometry` at *build* time
 * (`toEvaluationFeature`); here the laziness gates it at *read* time instead, which is strictly
 * later and so also covers a `within` that a paint property reads rather than a filter.
 *
 * **Divergence: [canonical] rides on the feature.** Upstream passes the tile id beside the feature
 * everywhere (`filter(globals, feature, canonical)`,
 * `populatePaintArrays(…, {imagePositions, canonical})`) because its paint path hands over a
 * `BucketFeature` that has no room for it. Here one [EvalFeature] is built per (feature, tile) and
 * already reaches every painter, every `processAs*` helper and the symbol layout pass, so carrying
 * the id on it is equivalent — both are per-tile — and means no property-evaluation call site has
 * to thread it. `within` and `distance` are the only readers; a feature built without one (a
 * synthetic feature, a test fixture) makes them return `false` / `NaN`, exactly as upstream does
 * when `canonical` is `undefined`.
 */
class EvalFeature(
    val type: String,
    val id: Any? = null,
    val properties: Map<String, Any?> = emptyMap(),
    /** The tile this feature was decoded from. See the note above; needed by `within`/`distance`. */
    val canonical: CanonicalTileId? = null,
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
