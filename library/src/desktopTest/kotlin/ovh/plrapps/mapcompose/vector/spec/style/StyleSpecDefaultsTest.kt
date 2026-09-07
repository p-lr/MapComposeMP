package ovh.plrapps.mapcompose.vector.spec.style

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mapcompose_mp.library.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import ovh.plrapps.mapcompose.vector.spec.style.utils.ColorParser
import kotlin.test.Test
import kotlin.test.fail

/**
 * Asserts [StyleSpecDefaults] still matches the published MapLibre style spec.
 *
 * The painters fall back to [StyleSpecDefaults] whenever a property is absent or its expression
 * failed to compile, so a value drifting from the spec is a silent rendering difference -- exactly
 * the kind of thing nobody notices until a style looks subtly wrong. The spec's own property table
 * is vendored into `files/style-spec-defaults.json` by
 * `library/tools/fetch-style-spec-defaults.sh`; this compares the two.
 *
 * It lives in `desktopTest` rather than `commonTest` because it reads a Compose resource, and
 * Compose Resources are unavailable on androidHostTest -- `Res.readBytes` there fails with
 * "No instrumentation registered", which is also why the style-parsing tests sit in `skiaTest`.
 * [StyleSpecDefaults] is common code with no platform behaviour, so asserting it once on the JVM
 * covers every target.
 *
 * Only the properties this object claims are checked. Properties whose spec default is `undefined`
 * are not representable as a constant and are handled per-painter instead -- `fill-outline-color`
 * falls back to the *evaluated* `fill-color`, for instance -- so they are listed in
 * [EXPECTED_UNDEFINED] and asserted to still have no default rather than to equal one.
 */
class StyleSpecDefaultsTest {

    @OptIn(ExperimentalResourceApi::class)
    @Test
    fun `style spec defaults match upstream`() = runTest {
        val text = Res.readBytes("files/style-spec-defaults.json").decodeToString()
        val properties = Json.parseToJsonElement(text).jsonObject
            .getValue("properties").jsonObject

        val failures = mutableListOf<String>()
        fun note(message: String) { failures += message }

        fun spec(block: String, property: String): JsonObject? =
            (properties[block] as? JsonObject)?.get(property)?.jsonObject

        fun checkNumber(block: String, property: String, actual: Double) {
            val entry = spec(block, property)
                ?: return note("$block/$property is not in the vendored spec table")
            val expected = entry["default"]?.jsonPrimitive?.doubleOrNull
                ?: return note("$block/$property has no numeric default upstream")
            if (expected != actual) failures.add("$block/$property expected $expected but was $actual")
        }

        fun checkString(block: String, property: String, actual: String) {
            val entry = spec(block, property)
                ?: return note("$block/$property is not in the vendored spec table")
            val expected = entry["default"]?.jsonPrimitive?.contentOrNull
                ?: return note("$block/$property has no string default upstream")
            if (expected != actual) failures.add("$block/$property expected '$expected' but was '$actual'")
        }

        fun checkBoolean(block: String, property: String, actual: Boolean) {
            val entry = spec(block, property)
                ?: return note("$block/$property is not in the vendored spec table")
            val expected = (entry["default"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                ?: return note("$block/$property has no boolean default upstream")
            if (expected != actual) failures.add("$block/$property expected $expected but was $actual")
        }

        fun checkColor(block: String, property: String, actual: Color) {
            val entry = spec(block, property)
                ?: return note("$block/$property is not in the vendored spec table")
            val raw = entry["default"]?.jsonPrimitive?.contentOrNull
                ?: return note("$block/$property has no colour default upstream")
            val expected = ColorParser.parseColorStringOrNull(raw)
                ?: return note("$block/$property default '$raw' is not a parseable colour")
            if (expected != actual) failures.add("$block/$property expected $expected ('$raw') but was $actual")
        }

        /**
         * `heatmap-color` is the one default that is an expression, so it is compared as parsed
         * JSON -- whitespace and number formatting in the vendored table are not the point.
         */
        fun checkExpression(block: String, property: String, actual: String) {
            val entry = spec(block, property)
                ?: return note("$block/$property is not in the vendored spec table")
            val expected = entry["default"]
                ?: return note("$block/$property has no default upstream")
            val parsed = runCatching { Json.parseToJsonElement(actual) }.getOrNull()
                ?: return note("$block/$property default in StyleSpecDefaults is not valid JSON")
            if (expected != parsed) {
                failures.add("$block/$property expected $expected but was $parsed")
            }
        }

        fun checkNumberPair(block: String, property: String, actual: List<Double>) {
            val entry = spec(block, property)
                ?: return note("$block/$property is not in the vendored spec table")
            val expected = entry["default"]?.jsonArray?.map { it.jsonPrimitive.double() }
                ?: return note("$block/$property has no array default upstream")
            if (expected != actual) failures.add("$block/$property expected $expected but was $actual")
        }

        /**
         * Upstream types `icon-padding` as `padding`, whose default is an array covering the four
         * sides. Only a uniform padding is modelled here, so every side of the spec default must
         * equal the single constant.
         */
        fun checkPadding(block: String, property: String, actual: Double) {
            val entry = spec(block, property)
                ?: return note("$block/$property is not in the vendored spec table")
            val expected = entry["default"]?.jsonArray?.map { it.jsonPrimitive.double() }
                ?: return note("$block/$property has no array default upstream")
            if (expected.any { it != actual }) {
                failures.add("$block/$property expected every side to be $actual but was $expected")
            }
        }

        fun checkStringList(block: String, property: String, actual: List<String>) {
            val entry = spec(block, property)
                ?: return note("$block/$property is not in the vendored spec table")
            val expected = entry["default"]?.jsonArray?.map { it.jsonPrimitive.content }
                ?: return note("$block/$property has no array default upstream")
            if (expected != actual) failures.add("$block/$property expected $expected but was $actual")
        }

        with(StyleSpecDefaults) {
            checkColor("paint_fill", "fill-color", FILL_COLOR)
            checkNumber("paint_fill", "fill-opacity", FILL_OPACITY)
            checkNumber("paint_fill", "fill-layer-opacity", FILL_LAYER_OPACITY)
            checkBoolean("paint_fill", "fill-antialias", FILL_ANTIALIAS)
            checkNumberPair("paint_fill", "fill-translate", FILL_TRANSLATE)
            checkString("paint_fill", "fill-translate-anchor", FILL_TRANSLATE_ANCHOR)

            checkColor("paint_line", "line-color", LINE_COLOR)
            checkNumber("paint_line", "line-opacity", LINE_OPACITY)
            checkNumber("paint_line", "line-layer-opacity", LINE_LAYER_OPACITY)
            checkNumber("paint_line", "line-width", LINE_WIDTH)
            checkNumber("paint_line", "line-gap-width", LINE_GAP_WIDTH)
            checkNumber("paint_line", "line-offset", LINE_OFFSET)
            checkNumber("paint_line", "line-blur", LINE_BLUR)
            checkNumberPair("paint_line", "line-translate", LINE_TRANSLATE)
            checkString("paint_line", "line-translate-anchor", LINE_TRANSLATE_ANCHOR)

            checkString("layout_line", "line-cap", LINE_CAP)
            checkString("layout_line", "line-join", LINE_JOIN)
            checkNumber("layout_line", "line-miter-limit", LINE_MITER_LIMIT)
            checkNumber("layout_line", "line-round-limit", LINE_ROUND_LIMIT)

            checkNumber("paint_circle", "circle-radius", CIRCLE_RADIUS)
            checkColor("paint_circle", "circle-color", CIRCLE_COLOR)
            checkNumber("paint_circle", "circle-blur", CIRCLE_BLUR)
            checkNumber("paint_circle", "circle-opacity", CIRCLE_OPACITY)
            checkNumberPair("paint_circle", "circle-translate", CIRCLE_TRANSLATE)
            checkString("paint_circle", "circle-translate-anchor", CIRCLE_TRANSLATE_ANCHOR)
            checkString("paint_circle", "circle-pitch-scale", CIRCLE_PITCH_SCALE)
            checkString("paint_circle", "circle-pitch-alignment", CIRCLE_PITCH_ALIGNMENT)
            checkNumber("paint_circle", "circle-stroke-width", CIRCLE_STROKE_WIDTH)
            checkColor("paint_circle", "circle-stroke-color", CIRCLE_STROKE_COLOR)
            checkNumber("paint_circle", "circle-stroke-opacity", CIRCLE_STROKE_OPACITY)

            checkColor("paint_background", "background-color", BACKGROUND_COLOR)
            checkNumber("paint_background", "background-opacity", BACKGROUND_OPACITY)

            checkNumber("paint_raster", "raster-opacity", RASTER_OPACITY)
            checkNumber("paint_raster", "raster-hue-rotate", RASTER_HUE_ROTATE)
            checkNumber("paint_raster", "raster-brightness-min", RASTER_BRIGHTNESS_MIN)
            checkNumber("paint_raster", "raster-brightness-max", RASTER_BRIGHTNESS_MAX)
            checkNumber("paint_raster", "raster-saturation", RASTER_SATURATION)
            checkNumber("paint_raster", "raster-contrast", RASTER_CONTRAST)
            checkString("paint_raster", "raster-resampling", RASTER_RESAMPLING)
            checkNumber("paint_raster", "raster-fade-duration", RASTER_FADE_DURATION)

            checkNumber("paint_hillshade", "hillshade-exaggeration", HILLSHADE_EXAGGERATION)
            checkNumber(
                "paint_hillshade", "hillshade-illumination-direction", HILLSHADE_ILLUMINATION_DIRECTION
            )
            checkString(
                "paint_hillshade", "hillshade-illumination-anchor", HILLSHADE_ILLUMINATION_ANCHOR
            )
            checkColor("paint_hillshade", "hillshade-shadow-color", HILLSHADE_SHADOW_COLOR)
            checkColor("paint_hillshade", "hillshade-highlight-color", HILLSHADE_HIGHLIGHT_COLOR)
            checkColor("paint_hillshade", "hillshade-accent-color", HILLSHADE_ACCENT_COLOR)

            checkNumber("paint_color-relief", "color-relief-opacity", COLOR_RELIEF_OPACITY)
            // Not `color-relief-resampling`: this one property carries no layer-type prefix.
            checkString("paint_color-relief", "resampling", COLOR_RELIEF_RESAMPLING)

            checkNumber("paint_heatmap", "heatmap-weight", HEATMAP_WEIGHT)
            checkNumber("paint_heatmap", "heatmap-intensity", HEATMAP_INTENSITY)
            checkNumber("paint_heatmap", "heatmap-radius", HEATMAP_RADIUS)
            checkNumber("paint_heatmap", "heatmap-opacity", HEATMAP_OPACITY)
            checkExpression("paint_heatmap", "heatmap-color", HEATMAP_COLOR)

            checkBoolean("layout_symbol", "icon-allow-overlap", ICON_ALLOW_OVERLAP)
            checkString("layout_symbol", "icon-anchor", ICON_ANCHOR)
            checkBoolean("layout_symbol", "icon-ignore-placement", ICON_IGNORE_PLACEMENT)
            checkBoolean("layout_symbol", "icon-keep-upright", ICON_KEEP_UPRIGHT)
            checkNumberPair("layout_symbol", "icon-offset", ICON_OFFSET)
            checkBoolean("layout_symbol", "icon-optional", ICON_OPTIONAL)
            checkPadding("layout_symbol", "icon-padding", ICON_PADDING)
            checkString("layout_symbol", "icon-pitch-alignment", ICON_PITCH_ALIGNMENT)
            checkNumber("layout_symbol", "icon-rotate", ICON_ROTATE)
            checkString("layout_symbol", "icon-rotation-alignment", ICON_ROTATION_ALIGNMENT)
            checkNumber("layout_symbol", "icon-size", ICON_SIZE)
            checkString("layout_symbol", "icon-text-fit", ICON_TEXT_FIT)
            checkNumberPair("layout_symbol", "icon-text-fit-padding", ICON_TEXT_FIT_PADDING)
            checkBoolean("layout_symbol", "symbol-avoid-edges", SYMBOL_AVOID_EDGES)
            checkString("layout_symbol", "symbol-placement", SYMBOL_PLACEMENT)
            checkNumber("layout_symbol", "symbol-spacing", SYMBOL_SPACING)
            checkString("layout_symbol", "symbol-z-order", SYMBOL_Z_ORDER)
            checkBoolean("layout_symbol", "text-allow-overlap", TEXT_ALLOW_OVERLAP)
            checkString("layout_symbol", "text-anchor", TEXT_ANCHOR)
            checkString("layout_symbol", "text-field", TEXT_FIELD)
            checkStringList("layout_symbol", "text-font", TEXT_FONT)
            checkBoolean("layout_symbol", "text-ignore-placement", TEXT_IGNORE_PLACEMENT)
            checkString("layout_symbol", "text-justify", TEXT_JUSTIFY)
            checkBoolean("layout_symbol", "text-keep-upright", TEXT_KEEP_UPRIGHT)
            checkNumber("layout_symbol", "text-letter-spacing", TEXT_LETTER_SPACING)
            checkNumber("layout_symbol", "text-line-height", TEXT_LINE_HEIGHT)
            checkNumber("layout_symbol", "text-max-angle", TEXT_MAX_ANGLE)
            checkNumber("layout_symbol", "text-max-width", TEXT_MAX_WIDTH)
            checkNumberPair("layout_symbol", "text-offset", TEXT_OFFSET)
            checkBoolean("layout_symbol", "text-optional", TEXT_OPTIONAL)
            checkNumber("layout_symbol", "text-padding", TEXT_PADDING)
            checkString("layout_symbol", "text-pitch-alignment", TEXT_PITCH_ALIGNMENT)
            checkNumber("layout_symbol", "text-radial-offset", TEXT_RADIAL_OFFSET)
            checkNumber("layout_symbol", "text-rotate", TEXT_ROTATE)
            checkString("layout_symbol", "text-rotation-alignment", TEXT_ROTATION_ALIGNMENT)
            checkNumber("layout_symbol", "text-size", TEXT_SIZE)
            checkString("layout_symbol", "text-transform", TEXT_TRANSFORM)

            checkColor("paint_symbol", "icon-color", ICON_COLOR)
            checkNumber("paint_symbol", "icon-halo-blur", ICON_HALO_BLUR)
            checkColor("paint_symbol", "icon-halo-color", ICON_HALO_COLOR)
            checkNumber("paint_symbol", "icon-halo-width", ICON_HALO_WIDTH)
            checkNumber("paint_symbol", "icon-opacity", ICON_OPACITY)
            checkNumberPair("paint_symbol", "icon-translate", ICON_TRANSLATE)
            checkString("paint_symbol", "icon-translate-anchor", ICON_TRANSLATE_ANCHOR)
            checkColor("paint_symbol", "text-color", TEXT_COLOR)
            checkNumber("paint_symbol", "text-halo-blur", TEXT_HALO_BLUR)
            checkColor("paint_symbol", "text-halo-color", TEXT_HALO_COLOR)
            checkNumber("paint_symbol", "text-halo-width", TEXT_HALO_WIDTH)
            checkNumber("paint_symbol", "text-opacity", TEXT_OPACITY)
            checkNumberPair("paint_symbol", "text-translate", TEXT_TRANSLATE)
            checkString("paint_symbol", "text-translate-anchor", TEXT_TRANSLATE_ANCHOR)

            checkString("layout_fill", "visibility", VISIBILITY)
        }

        for ((block, property) in EXPECTED_UNDEFINED) {
            val entry = spec(block, property)
            if (entry == null) {
                failures.add("$block/$property is not in the vendored spec table")
            } else if (entry["default"] !is kotlinx.serialization.json.JsonNull && entry["default"] != null) {
                failures.add(
                    "$block/$property now has a spec default (${entry["default"]}); " +
                        "StyleSpecDefaults should gain a constant for it"
                )
            }
        }

        if (failures.isNotEmpty()) {
            fail("StyleSpecDefaults disagrees with the vendored spec table\n" + failures.joinToString("\n"))
        }
    }

    private companion object {
        /** Properties whose spec default is `undefined`, handled per-painter rather than as a constant. */
        val EXPECTED_UNDEFINED = listOf(
            "paint_fill" to "fill-outline-color",
            "paint_fill" to "fill-pattern",
            "paint_line" to "line-dasharray",
            "paint_line" to "line-pattern",
            "paint_line" to "line-gradient",
            "paint_background" to "background-pattern",
            "layout_fill" to "fill-sort-key",
            "layout_line" to "line-sort-key",
            "layout_circle" to "circle-sort-key",
            "layout_symbol" to "symbol-sort-key",
            "layout_symbol" to "icon-image",
            "layout_symbol" to "icon-overlap",
            "layout_symbol" to "text-overlap",
            "layout_symbol" to "text-variable-anchor",
            "layout_symbol" to "text-variable-anchor-offset",
            "layout_symbol" to "text-writing-mode",
            "paint_color-relief" to "color-relief-color",
        )
    }
}

private fun JsonPrimitive.double(): Double =
    doubleOrNull ?: throw IllegalStateException("not a number: $this")
