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
 * `line-gradient`, every `*-pattern`, every `*-sort-key`, and on `symbol` also `icon-image`,
 * `icon-overlap`, `text-overlap`, `text-variable-anchor` and `text-variable-anchor-offset`, and on
 * `color-relief` also `color-relief-color`) are deliberately absent: "unset" is not a value, and
 * each painter handles it specifically -- `fill-outline-color`, for instance, falls back to the
 * *evaluated* `fill-color` rather than to a constant, and an absent `icon-overlap` means "read
 * `icon-allow-overlap` instead".
 */
object StyleSpecDefaults {

    // paint_fill
    val FILL_COLOR = Color.Black
    const val FILL_OPACITY = 1.0
    const val FILL_LAYER_OPACITY = 1.0
    const val FILL_ANTIALIAS = true
    val FILL_TRANSLATE = listOf(0.0, 0.0)
    const val FILL_TRANSLATE_ANCHOR = ANCHOR_MAP

    // paint_line
    val LINE_COLOR = Color.Black
    const val LINE_OPACITY = 1.0
    const val LINE_LAYER_OPACITY = 1.0
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

    // paint_hillshade
    const val HILLSHADE_EXAGGERATION = 0.5
    const val HILLSHADE_ILLUMINATION_DIRECTION = 335.0
    const val HILLSHADE_ILLUMINATION_ANCHOR = ANCHOR_VIEWPORT
    val HILLSHADE_SHADOW_COLOR = Color.Black
    val HILLSHADE_HIGHLIGHT_COLOR = Color.White
    val HILLSHADE_ACCENT_COLOR = Color.Black

    /* paint_color-relief. `color-relief-color` has no spec default -- "unset" is not a colour, and
     * without a ramp there is nothing to draw -- so it is handled at the painter, like every other
     * undefined-default property. */
    const val COLOR_RELIEF_OPACITY = 1.0
    const val COLOR_RELIEF_RESAMPLING = RESAMPLING_LINEAR

    // paint_heatmap
    const val HEATMAP_WEIGHT = 1.0
    const val HEATMAP_INTENSITY = 1.0
    const val HEATMAP_RADIUS = 30.0
    const val HEATMAP_OPACITY = 1.0

    /**
     * `heatmap-color`, the one spec default that is an expression rather than a scalar.
     *
     * It is kept as its JSON text and compiled with the ordinary property serializer, so the ramp a
     * style gets when it says nothing is byte-for-byte the ramp it would get by spelling this out --
     * including how `interpolate` blends the stops. See
     * [ovh.plrapps.mapcompose.vector.renderer.HeatmapLayerPainter].
     */
    const val HEATMAP_COLOR: String =
        """["interpolate",["linear"],["heatmap-density"],""" +
            """0,"rgba(0, 0, 255, 0)",0.1,"royalblue",0.3,"cyan",""" +
            """0.5,"lime",0.7,"yellow",1,"red"]"""

    // layout_symbol
    const val ICON_ALLOW_OVERLAP = false
    const val ICON_ANCHOR = ANCHOR_CENTER
    const val ICON_IGNORE_PLACEMENT = false
    const val ICON_KEEP_UPRIGHT = false
    val ICON_OFFSET = listOf(0.0, 0.0)
    const val ICON_OPTIONAL = false

    /**
     * Upstream types `icon-padding` as `padding`, whose default is the one-element array `[2]` --
     * "the same padding on all four sides". Only a uniform padding is modelled here, so the
     * constant is that single value; a style spelling out four different sides falls back to it.
     */
    const val ICON_PADDING = 2.0
    const val ICON_PITCH_ALIGNMENT = ALIGNMENT_AUTO
    const val ICON_ROTATE = 0.0
    const val ICON_ROTATION_ALIGNMENT = ALIGNMENT_AUTO
    const val ICON_SIZE = 1.0
    const val ICON_TEXT_FIT = ICON_TEXT_FIT_NONE
    val ICON_TEXT_FIT_PADDING = listOf(0.0, 0.0, 0.0, 0.0)
    const val SYMBOL_AVOID_EDGES = false
    const val SYMBOL_PLACEMENT = SYMBOL_PLACEMENT_POINT
    const val SYMBOL_SPACING = 250.0
    const val SYMBOL_Z_ORDER = SYMBOL_Z_ORDER_AUTO
    const val TEXT_ALLOW_OVERLAP = false
    const val TEXT_ANCHOR = ANCHOR_CENTER
    const val TEXT_FIELD = ""
    val TEXT_FONT = listOf("Open Sans Regular", "Arial Unicode MS Regular")
    const val TEXT_IGNORE_PLACEMENT = false
    const val TEXT_JUSTIFY = TEXT_JUSTIFY_CENTER
    const val TEXT_KEEP_UPRIGHT = true
    const val TEXT_LETTER_SPACING = 0.0
    const val TEXT_LINE_HEIGHT = 1.2
    const val TEXT_MAX_ANGLE = 45.0
    const val TEXT_MAX_WIDTH = 10.0
    val TEXT_OFFSET = listOf(0.0, 0.0)
    const val TEXT_OPTIONAL = false
    const val TEXT_PADDING = 2.0
    const val TEXT_PITCH_ALIGNMENT = ALIGNMENT_AUTO
    const val TEXT_RADIAL_OFFSET = 0.0
    const val TEXT_ROTATE = 0.0
    const val TEXT_ROTATION_ALIGNMENT = ALIGNMENT_AUTO
    const val TEXT_SIZE = 16.0
    const val TEXT_TRANSFORM = TEXT_TRANSFORM_NONE

    // paint_symbol
    val ICON_COLOR = Color.Black
    const val ICON_HALO_BLUR = 0.0
    val ICON_HALO_COLOR = Color.Transparent
    const val ICON_HALO_WIDTH = 0.0
    const val ICON_OPACITY = 1.0
    val ICON_TRANSLATE = listOf(0.0, 0.0)
    const val ICON_TRANSLATE_ANCHOR = ANCHOR_MAP
    val TEXT_COLOR = Color.Black
    const val TEXT_HALO_BLUR = 0.0
    val TEXT_HALO_COLOR = Color.Transparent
    const val TEXT_HALO_WIDTH = 0.0
    const val TEXT_OPACITY = 1.0
    val TEXT_TRANSLATE = listOf(0.0, 0.0)
    const val TEXT_TRANSLATE_ANCHOR = ANCHOR_MAP

    // layout_*
    const val VISIBILITY = "visible"
    const val VISIBILITY_NONE = "none"
}

/** `*-rotation-alignment` / `*-pitch-alignment` enum value: pick from `symbol-placement`. */
const val ALIGNMENT_AUTO = "auto"

/** `icon-anchor` / `text-anchor` enum value: the box is centred on the point. */
const val ANCHOR_CENTER = "center"

/** `symbol-placement` enum values. */
const val SYMBOL_PLACEMENT_POINT = "point"
const val SYMBOL_PLACEMENT_LINE = "line"
const val SYMBOL_PLACEMENT_LINE_CENTER = "line-center"

/** `symbol-z-order` enum values. */
const val SYMBOL_Z_ORDER_AUTO = "auto"
const val SYMBOL_Z_ORDER_VIEWPORT_Y = "viewport-y"
const val SYMBOL_Z_ORDER_SOURCE = "source"

/** `text-justify` enum values. */
const val TEXT_JUSTIFY_AUTO = "auto"
const val TEXT_JUSTIFY_LEFT = "left"
const val TEXT_JUSTIFY_CENTER = "center"
const val TEXT_JUSTIFY_RIGHT = "right"

/** `text-transform` enum values. */
const val TEXT_TRANSFORM_NONE = "none"
const val TEXT_TRANSFORM_UPPERCASE = "uppercase"
const val TEXT_TRANSFORM_LOWERCASE = "lowercase"

/** `text-writing-mode` enum values. */
const val WRITING_MODE_HORIZONTAL = "horizontal"
const val WRITING_MODE_VERTICAL = "vertical"

/** `icon-text-fit` enum values. */
const val ICON_TEXT_FIT_NONE = "none"
const val ICON_TEXT_FIT_WIDTH = "width"
const val ICON_TEXT_FIT_HEIGHT = "height"
const val ICON_TEXT_FIT_BOTH = "both"

/** `icon-overlap` / `text-overlap` enum values. */
const val OVERLAP_NEVER = "never"
const val OVERLAP_ALWAYS = "always"
const val OVERLAP_COOPERATIVE = "cooperative"

/** `*-translate-anchor` / `*-pitch-*` enum value: the property is relative to the map. */
const val ANCHOR_MAP = "map"

/** `*-translate-anchor` / `*-pitch-*` enum value: the property is relative to the viewport. */
const val ANCHOR_VIEWPORT = "viewport"

/** `raster-resampling` enum value: bilinear filtering, which smooths a magnified tile. */
const val RESAMPLING_LINEAR = "linear"

/** `raster-resampling` enum value: nearest-neighbour, which keeps a magnified tile's hard edges. */
const val RESAMPLING_NEAREST = "nearest"
