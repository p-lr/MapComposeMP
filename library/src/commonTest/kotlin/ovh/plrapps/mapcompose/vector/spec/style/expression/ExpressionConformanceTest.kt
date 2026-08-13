package ovh.plrapps.mapcompose.vector.spec.style.expression

import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mapcompose_mp.library.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.Formatted
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.ResolvedImage
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.EXTENT
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.getTileCoordinates
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.fail

/**
 * Runs MapLibre's own expression conformance suite against this engine.
 *
 * The fixtures are vendored verbatim from `maplibre-style-spec/test/integration/expression/tests`
 * (577 cases across 89 operators), flattened into one bundled resource by
 * `library/tools/fetch-expression-fixtures.sh`. Each fixture supplies an expression, a list of
 * `[globals, feature]` inputs, the expected compilation result, and the expected output per input.
 *
 * What is asserted: the compile result (`success`/`error`), the inferred output type, the
 * `isFeatureConstant` / `isZoomConstant` static analysis, and every evaluated output. Upstream error
 * *messages* are not compared — only that an error occurred — because they embed JavaScript type
 * names and JSON formatting that this port has no reason to reproduce exactly.
 *
 * [KNOWN_DIVERGENCES] lists the cases this port deliberately does not satisfy, each with a reason.
 */
@OptIn(ExperimentalTestApi::class)
class ExpressionConformanceTest {

    @OptIn(ExperimentalResourceApi::class)
    @Test
    fun `maplibre expression conformance suite`() = runComposeUiTest {
        var bundle: JsonObject? = null

        setContent {
            val loaded by produceState<JsonObject?>(null) {
                val text = Res.readBytes("files/expression-tests.json").decodeToString()
                value = json.parseToJsonElement(text).jsonObject
            }
            bundle = loaded
        }

        waitUntil(timeoutMillis = 30_000) { bundle != null }

        val tests = bundle!!["tests"]!!.jsonObject
        val failures = mutableListOf<String>()
        var ran = 0
        var skipped = 0

        for ((name, fixtureElement) in tests) {
            if (KNOWN_DIVERGENCES.containsKey(name)) {
                skipped++
                continue
            }
            val fixture = fixtureElement.jsonObject
            val result = try {
                runFixture(name, fixture)
            } catch (e: Throwable) {
                // A throw out of the parser is itself a conformance failure: upstream collects
                // parse errors rather than throwing.
                FixtureResult.Failed("threw ${e::class.simpleName}: ${e.message}")
            }
            when (result) {
                is FixtureResult.Skipped -> skipped++
                is FixtureResult.Passed -> ran++
                is FixtureResult.Failed -> {
                    ran++
                    failures.add("$name: ${result.reason}")
                }
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                "${failures.size} of $ran conformance fixtures failed ($skipped skipped):\n" +
                        failures.take(40).joinToString("\n") +
                        if (failures.size > 40) "\n… and ${failures.size - 40} more" else ""
            )
        }
    }

    private sealed class FixtureResult {
        data object Passed : FixtureResult()
        data object Skipped : FixtureResult()
        data class Failed(val reason: String) : FixtureResult()
    }

    private fun runFixture(name: String, fixture: JsonObject): FixtureResult {
        val expression = jsonToValue(fixture["expression"] ?: return FixtureResult.Skipped)
        val expected = fixture["expected"]?.jsonObject ?: return FixtureResult.Skipped
        val compiledExpected = expected["compiled"]?.jsonObject ?: return FixtureResult.Skipped

        // Upstream's harness completes a partial propertySpec (data-driven, interpolated, zoom +
        // feature parameters) and always compiles through createPropertyExpression, so the static
        // analysis is exercised on every fixture.
        val spec = parsePropertySpec(fixture["propertySpec"]?.jsonObject) ?: return FixtureResult.Skipped

        val globalState = fixture["globalState"]?.jsonObject
            ?.mapValues { (_, v) -> jsonToValue(v) }

        val expectedResult = compiledExpected["result"]?.jsonPrimitive?.content

        // A bare object is a legacy v7 function; upstream's harness converts it before compiling.
        @Suppress("UNCHECKED_CAST")
        val toCompile = if (expression is Map<*, *>) {
            convertLegacyFunction(expression as Map<String, Any?>, spec)
        } else {
            expression
        }

        val propertyExpression = when (
            val compiled = createPropertyExpression(toCompile, ROOT_KEY, spec, globalState)
        ) {
            is ExpressionResult.Error ->
                return if (expectedResult == "error") FixtureResult.Passed
                else FixtureResult.Failed("expected success, got errors ${compiled.errors}")

            is ExpressionResult.Success -> {
                if (expectedResult == "error") {
                    return FixtureResult.Failed("expected compile error, but it compiled")
                }
                compiled.value
            }
        }
        val styleExpression = propertyExpression.styleExpression

        val expectedType = compiledExpected["type"]?.jsonPrimitive?.content
        if (expectedType != null) {
            val actualType = typeToString(styleExpression.expression.type)
            if (actualType != expectedType) {
                return FixtureResult.Failed("type: expected $expectedType, got $actualType")
            }
        }

        val kind = propertyExpression.kind
        val expectedFeatureConstant = compiledExpected["isFeatureConstant"]?.jsonPrimitive?.booleanOrNull
        if (expectedFeatureConstant != null) {
            val actual = kind == EvaluationKind.CONSTANT || kind == EvaluationKind.CAMERA
            if (actual != expectedFeatureConstant) {
                return FixtureResult.Failed("isFeatureConstant: expected $expectedFeatureConstant, got $actual")
            }
        }

        val expectedZoomConstant = compiledExpected["isZoomConstant"]?.jsonPrimitive?.booleanOrNull
        if (expectedZoomConstant != null) {
            val actual = kind == EvaluationKind.CONSTANT || kind == EvaluationKind.SOURCE
            if (actual != expectedZoomConstant) {
                return FixtureResult.Failed("isZoomConstant: expected $expectedZoomConstant, got $actual")
            }
        }

        val inputs = fixture["inputs"]?.jsonArray ?: return FixtureResult.Passed
        val outputs = expected["outputs"]?.jsonArray ?: return FixtureResult.Passed

        for (i in inputs.indices) {
            val input = inputs[i].jsonArray
            val globals = parseGlobals(input.getOrNull(0))
            val canonical = parseCanonical(input.getOrNull(0))
            val featureJson = input.getOrNull(1)?.jsonObject
            val feature = parseFeature(featureJson, canonical)
            val featureState = featureJson?.get("featureState")?.jsonObject
                ?.mapValues { (_, v) -> jsonToValue(v) }
            val availableImages = (input.getOrNull(0) as? JsonObject)
                ?.get("availableImages")?.jsonArray?.map { it.jsonPrimitive.content }

            val expectedOutput = outputs.getOrNull(i) ?: continue
            val expectsError = expectedOutput is JsonObject && expectedOutput.containsKey("error")

            val actual = try {
                styleExpression.evaluateWithoutErrorHandling(
                    globals = globals,
                    feature = feature,
                    featureState = featureState,
                    canonical = canonical,
                    availableImages = availableImages,
                )
            } catch (e: Exception) {
                if (expectsError) continue
                return FixtureResult.Failed("input[$i]: unexpected error ${e.message}")
            }

            if (expectsError) {
                return FixtureResult.Failed("input[$i]: expected an error, got ${describe(actual)}")
            }
            if (!valueMatches(expectedOutput, actual)) {
                return FixtureResult.Failed("input[$i]: expected $expectedOutput, got ${describe(actual)}")
            }
        }

        return FixtureResult.Passed
    }

    private fun parseGlobals(element: JsonElement?): GlobalProperties {
        val obj = element as? JsonObject ?: return GlobalProperties(zoom = 0.0)
        return GlobalProperties(
            zoom = obj["zoom"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            heatmapDensity = obj["heatmapDensity"]?.jsonPrimitive?.doubleOrNull,
            elevation = obj["elevation"]?.jsonPrimitive?.doubleOrNull,
            lineProgress = obj["lineProgress"]?.jsonPrimitive?.doubleOrNull,
            accumulated = obj["accumulated"]?.let { jsonToValue(it) },
        )
    }

    private fun parseCanonical(element: JsonElement?): CanonicalTileId? {
        val obj = (element as? JsonObject)?.get("canonicalID") as? JsonObject ?: return null
        return CanonicalTileId(
            z = obj["z"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0,
            x = obj["x"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0,
            y = obj["y"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0,
        )
    }

    /**
     * Builds the feature, converting the fixture's GeoJSON geometry into tile-local coordinates.
     *
     * Ported from `test/lib/geometry.ts` in the upstream repo: the coordinates are projected with
     * `getTileCoordinates` and then shifted to be relative to the tile, and `Multi*` geometries
     * collapse to their singular type.
     */
    private fun parseFeature(obj: JsonObject?, canonical: CanonicalTileId?): EvalFeature? {
        if (obj == null) return null
        val properties = (obj["properties"] as? JsonObject)
            ?.mapValues { (_, v) -> jsonToValue(v) } ?: emptyMap()

        val geometryJson = obj["geometry"] as? JsonObject
        val geometryType = geometryJson?.get("type")?.jsonPrimitive?.content
        val type = when (geometryType) {
            "MultiPoint" -> "Point"
            "MultiLineString" -> "LineString"
            "MultiPolygon" -> "Polygon"
            null -> "Unknown"
            else -> geometryType
        }

        val rings: List<List<Point2D>>? =
            if (geometryJson != null && canonical != null) {
                val coordinates = geometryJson["coordinates"]
                when (geometryType) {
                    "Point" -> listOf(listOf(tilePoint(coordinates!!.jsonArray, canonical)))
                    "MultiPoint" -> coordinates!!.jsonArray.map { listOf(tilePoint(it.jsonArray, canonical)) }
                    "LineString" -> listOf(tileLine(coordinates!!.jsonArray, canonical))
                    "MultiLineString", "Polygon" -> coordinates!!.jsonArray.map { tileLine(it.jsonArray, canonical) }
                    "MultiPolygon" -> coordinates!!.jsonArray.flatMap { polygon ->
                        polygon.jsonArray.map { tileLine(it.jsonArray, canonical) }
                    }

                    else -> null
                }
            } else {
                null
            }

        return EvalFeature(
            type = type,
            id = obj["id"]?.let { jsonToValue(it) },
            properties = properties,
            geometryProvider = rings?.let { { it } },
        )
    }

    private fun tilePoint(position: JsonArray, canonical: CanonicalTileId): Point2D {
        val coord = getTileCoordinates(
            listOf(position[0].jsonPrimitive.doubleOrNull ?: 0.0, position[1].jsonPrimitive.doubleOrNull ?: 0.0),
            canonical,
        )
        // Shift so the point is relative to the tile rather than the world.
        return Point2D(coord[0] - canonical.x.toDouble() * EXTENT, coord[1] - canonical.y.toDouble() * EXTENT)
    }

    private fun tileLine(line: JsonArray, canonical: CanonicalTileId): List<Point2D> =
        line.map { tilePoint(it.jsonArray, canonical) }

    /** Returns null for property types this port deliberately does not model. */
    private fun parsePropertySpec(spec: JsonObject?): StylePropertySpec? {
        if (spec == null) {
            // The harness's completed default: data-driven, interpolated, zoom + feature.
            return StylePropertySpec(expectedType = null, supportsInterpolation = true)
        }
        val typeName = spec["type"]?.jsonPrimitive?.content
        val expectedType: ExprType? = when (typeName) {
            "number" -> NumberType
            "string" -> StringType
            "boolean" -> BooleanType
            "color" -> ColorType
            "formatted" -> FormattedType
            "resolvedImage" -> ResolvedImageType
            "null" -> NullType
            "enum" -> StringType
            "array" -> {
                val itemType = when (spec["value"]?.jsonPrimitive?.content) {
                    "number" -> NumberType
                    "string" -> StringType
                    "boolean" -> BooleanType
                    else -> ValueType
                }
                array(itemType, spec["length"]?.jsonPrimitive?.doubleOrNull?.toInt())
            }

            null -> null
            else -> return null // projectionDefinition, padding, colorArray, numberArray, …
        }

        val expression = spec["expression"]?.jsonObject
        val parameters = expression?.get("parameters")?.jsonArray?.map { it.jsonPrimitive.content }
            ?: listOf("zoom", "feature")

        return StylePropertySpec(
            expectedType = expectedType,
            defaultValue = spec["default"]?.let { jsonToValue(it) },
            enumValues = (spec["values"] as? JsonObject)?.keys,
            supportsPropertyExpression = "feature" in parameters,
            supportsZoomExpression = "zoom" in parameters,
            supportsInterpolation = expression?.get("interpolated")?.jsonPrimitive?.booleanOrNull ?: true,
            tokens = spec["tokens"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    private fun describe(value: Any?): String = when (value) {
        is Color -> "[${value.red}, ${value.green}, ${value.blue}, ${value.alpha}]"
        else -> valueToJsonString(value)
    }

    /**
     * Structural comparison between a fixture's expected JSON and an engine value.
     *
     * Colors are compared against upstream's `[r, g, b, a]` (0..1, unpremultiplied) encoding, and
     * numbers with a small tolerance because the fixtures were produced by a double-precision
     * JavaScript engine.
     */
    private fun valueMatches(expected: JsonElement, actual: Any?): Boolean = when {
        expected is JsonNull -> actual == null

        // Upstream serializes a color as its premultiplied [r, g, b, a] components.
        actual is Color -> expected is JsonArray && expected.size == 4 &&
                colorChannelMatches(expected[0], (actual.red * actual.alpha).toDouble()) &&
                colorChannelMatches(expected[1], (actual.green * actual.alpha).toDouble()) &&
                colorChannelMatches(expected[2], (actual.blue * actual.alpha).toDouble()) &&
                colorChannelMatches(expected[3], actual.alpha.toDouble())

        actual is Formatted -> expected is JsonObject && formattedMatches(expected, actual)

        actual is ResolvedImage -> expected is JsonObject &&
                expected["name"]?.jsonPrimitive?.content == actual.name &&
                expected["available"]?.jsonPrimitive?.booleanOrNull == actual.available

        expected is JsonArray -> actual is List<*> && expected.size == actual.size &&
                expected.indices.all { valueMatches(expected[it], actual[it]) }

        expected is JsonObject -> actual is Map<*, *> && expected.keys == actual.keys &&
                expected.all { (k, v) -> valueMatches(v, actual[k]) }

        expected is JsonPrimitive && actual is Number -> numberMatches(expected, actual.toDouble())

        expected is JsonPrimitive && actual is Boolean -> expected.booleanOrNull == actual

        expected is JsonPrimitive && actual is String -> expected.isString && expected.content == actual

        else -> false
    }

    /**
     * Colors are compared at 8-bit precision.
     *
     * `androidx.compose.ui.graphics.Color` stores sRGB channels as 8-bit values, while MapLibre
     * keeps them as doubles, so an interpolated midpoint is 128/255 here and exactly 0.5 upstream.
     * That is a storage difference in the host toolkit, not a difference in the expression engine,
     * and it is invisible at the 8-bit render target.
     */
    private fun colorChannelMatches(expected: JsonElement, actual: Double): Boolean {
        val e = (expected as? JsonPrimitive)?.doubleOrNull ?: return false
        return abs(e - actual) <= 1.0 / 255.0 + 1e-6
    }

    private fun formattedMatches(expected: JsonObject, actual: Formatted): Boolean {
        val sections = expected["sections"]?.jsonArray ?: return false
        if (sections.size != actual.sections.size) return false
        return sections.indices.all { i ->
            val e = sections[i].jsonObject
            val a = actual.sections[i]
            e["text"]?.jsonPrimitive?.content == a.text &&
                    imageMatches(e["image"], a.image) &&
                    nullableNumberMatches(e["scale"], a.scale) &&
                    nullableStringMatches(e["fontStack"], a.fontStack) &&
                    nullableColorMatches(e["textColor"], a.textColor) &&
                    nullableStringMatches(e["verticalAlign"], a.verticalAlign?.value)
        }
    }

    private fun imageMatches(expected: JsonElement?, actual: ResolvedImage?): Boolean = when {
        expected == null || expected is JsonNull -> actual == null
        expected is JsonObject -> actual != null &&
                expected["name"]?.jsonPrimitive?.content == actual.name &&
                expected["available"]?.jsonPrimitive?.booleanOrNull == actual.available

        else -> false
    }

    private fun nullableNumberMatches(expected: JsonElement?, actual: Double?): Boolean = when {
        expected == null || expected is JsonNull -> actual == null
        else -> actual != null && numberMatches(expected, actual)
    }

    private fun nullableStringMatches(expected: JsonElement?, actual: String?): Boolean = when {
        expected == null || expected is JsonNull -> actual == null
        else -> (expected as? JsonPrimitive)?.content == actual
    }

    private fun nullableColorMatches(expected: JsonElement?, actual: Color?): Boolean = when {
        expected == null || expected is JsonNull -> actual == null
        expected is JsonObject -> actual != null &&
                colorChannelMatches(expected["r"]!!, (actual.red * actual.alpha).toDouble()) &&
                colorChannelMatches(expected["g"]!!, (actual.green * actual.alpha).toDouble()) &&
                colorChannelMatches(expected["b"]!!, (actual.blue * actual.alpha).toDouble()) &&
                colorChannelMatches(expected["a"]!!, actual.alpha.toDouble())

        else -> false
    }

    private fun numberMatches(expected: JsonElement, actual: Double): Boolean {
        val e = (expected as? JsonPrimitive)?.doubleOrNull ?: return false
        if (e.isNaN() && actual.isNaN()) return true
        return stripPrecision(e) == stripPrecision(actual)
    }

    /**
     * Truncates to 6 significant decimal figures, matching `stripPrecision` in
     * `test/lib/json-diff.ts` — the fixtures' expected values were written out that way, so
     * comparison has to happen in the same space.
     */
    private fun stripPrecision(x: Double, decimalSigFigs: Int = 6): Double {
        if (x == 0.0 || x.isNaN() || x.isInfinite()) return x
        val multiplier = 10.0.pow(maxOf(0.0, decimalSigFigs - ceil(log10(abs(x)))))
        // Stripped twice, as upstream does, to avoid re-stripping shifting the value.
        val firstStrip = floor(x * multiplier) / multiplier
        return floor(firstStrip * multiplier) / multiplier
    }

    private companion object {
        const val ROOT_KEY = "layers[0].paint.some-property"

        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Fixtures this port deliberately does not satisfy, each with its reason.
         *
         * Three root causes, none of them in the expression engine itself:
         *
         *  1. **No ICU on Kotlin Multiplatform.** `Intl.Collator` and `Intl.NumberFormat` have no
         *     equivalent, so locale-tailored collation (German "ü" == "ue", Swedish "ä" after "z")
         *     and locale-aware number formatting (grouping separators, currency symbols, CLDR unit
         *     names) cannot be reproduced. `Collator` implements ICU-style three-level comparison
         *     and `NumberFormat` honours the fraction-digit options, which is what the other 16
         *     collator and number-format fixtures exercise.
         *  2. **`androidx.compose.ui.graphics.Color` stores sRGB channels as 8 bits.** MapLibre
         *     keeps them as doubles. Round-tripping a channel therefore yields 128/255 where
         *     upstream yields exactly 0.5. Invisible at the 8-bit render target; the color
         *     comparisons elsewhere in this suite allow ±1/255 for the same reason.
         *  3. **Property types MapCompose does not model** — `projectionDefinition`, `padding`,
         *     `numberArray`, `colorArray` and `variableAnchorOffsetCollection`. These are skipped
         *     by `parsePropertySpec` returning null rather than listed individually here; see the
         *     note on `ExprType`.
         */
        val KNOWN_DIVERGENCES: Map<String, String> = mapOf(
            "collator/accent-equals-de" to
                    "German collation expands 'ü' to 'ue'; that is ICU locale tailoring.",
            "collator/variable-gt" to
                    "Ordering of 'ä' against 'a' is locale-tailored (de/sv differ from dk/fr).",
            "collator/variable-lteq" to
                    "Ordering of 'ä' against 'a' is locale-tailored (de/sv differ from dk/fr).",
            "number-format/default" to
                    "No ICU: grouping separators are locale data we do not have.",
            "number-format/precision" to
                    "No ICU: grouping separators, plus Double runs out of precision past 15 digits.",
            "number-format/currency" to
                    "No ICU: currency symbols and their placement are locale data we do not have.",
            "number-format/unit" to
                    "No ICU: CLDR unit abbreviations are locale data we do not have.",
            "to-rgba/alpha" to
                    "Compose Color stores 8-bit channels, so alpha round-trips as 128/255, not 0.5.",
            "to-string/color" to
                    "Compose Color stores 8-bit channels, so the alpha renders as 0.502, not 0.5.",
        )
    }
}
