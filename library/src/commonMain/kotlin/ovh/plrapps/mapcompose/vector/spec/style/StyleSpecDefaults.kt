package ovh.plrapps.mapcompose.vector.spec.style

import androidx.compose.ui.graphics.Color

/**
 * The MapLibre style-spec defaults the layer painters fall back to.
 *
 * A style property that is absent -- or whose expression failed to compile, and so evaluates to
 * `null` (see [ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue.Invalid]) -- must
 * render as upstream's documented default, not as whatever literal happened to be typed at the
 * `?:` in a painter. Painters therefore read every fallback from here.
 *
 * The values mirror `src/reference/v8.json` in `maplibre/maplibre-style-spec`.
 * `library/tools/fetch-style-spec-defaults.sh` vendors that table into
 * `commonTest/composeResources/files/style-spec-defaults.json`, and `StyleSpecDefaultsTest` asserts
 * this object still agrees with it -- so a spec bump surfaces as a test failure rather than a
 * silent rendering difference.
 *
 * Properties whose spec default is `undefined` (`fill-outline-color`, `line-dasharray`,
 * `line-gradient`, every `*-pattern`, every `*-sort-key`) are deliberately absent: "unset" is not a
 * value, and each painter handles it specifically -- `fill-outline-color`, for instance, falls back
 * to the *evaluated* `fill-color` rather than to a constant.
 */
object StyleSpecDefaults {

    // paint_fill
    val FILL_COLOR = Color.Black
    const val FILL_OPACITY = 1.0
    const val FILL_ANTIALIAS = true
    val FILL_TRANSLATE = listOf(0.0, 0.0)
    const val FILL_TRANSLATE_ANCHOR = ANCHOR_MAP

    // paint_line
    val LINE_COLOR = Color.Black
    const val LINE_OPACITY = 1.0
    const val LINE_WIDTH = 1.0
    const val LINE_GAP_WIDTH = 0.0
    const val LINE_OFFSET = 0.0
    const val LINE_BLUR = 0.0
    val LINE_TRANSLATE = listOf(0.0, 0.0)
    const val LINE_TRANSLATE_ANCHOR = ANCHOR_MAP

    // layout_line
    const val LINE_CAP = "butt"
    const val LINE_JOIN = "miter"
    const val LINE_MITER_LIMIT = 2.0
    const val LINE_ROUND_LIMIT = 1.05

    // paint_circle
    const val CIRCLE_RADIUS = 5.0
    val CIRCLE_COLOR = Color.Black
    const val CIRCLE_BLUR = 0.0
    const val CIRCLE_OPACITY = 1.0
    val CIRCLE_TRANSLATE = listOf(0.0, 0.0)
    const val CIRCLE_TRANSLATE_ANCHOR = ANCHOR_MAP
    const val CIRCLE_PITCH_SCALE = ANCHOR_MAP
    const val CIRCLE_PITCH_ALIGNMENT = ANCHOR_VIEWPORT
    const val CIRCLE_STROKE_WIDTH = 0.0
    val CIRCLE_STROKE_COLOR = Color.Black
    const val CIRCLE_STROKE_OPACITY = 1.0

    // paint_background
    val BACKGROUND_COLOR = Color.Black
    const val BACKGROUND_OPACITY = 1.0

    // paint_raster
    const val RASTER_OPACITY = 1.0
    const val RASTER_HUE_ROTATE = 0.0
    const val RASTER_BRIGHTNESS_MIN = 0.0
    const val RASTER_BRIGHTNESS_MAX = 1.0
    const val RASTER_SATURATION = 0.0
    const val RASTER_CONTRAST = 0.0
    const val RASTER_RESAMPLING = RESAMPLING_LINEAR
    const val RASTER_FADE_DURATION = 300.0

    // layout_*
    const val VISIBILITY = "visible"
    const val VISIBILITY_NONE = "none"
}

/** `*-translate-anchor` / `*-pitch-*` enum value: the property is relative to the map. */
const val ANCHOR_MAP = "map"

/** `*-translate-anchor` / `*-pitch-*` enum value: the property is relative to the viewport. */
const val ANCHOR_VIEWPORT = "viewport"

/** `raster-resampling` enum value: bilinear filtering, which smooths a magnified tile. */
const val RESAMPLING_LINEAR = "linear"

/** `raster-resampling` enum value: nearest-neighbour, which keeps a magnified tile's hard edges. */
const val RESAMPLING_NEAREST = "nearest"
