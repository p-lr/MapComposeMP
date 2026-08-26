package ovh.plrapps.mapcompose.vector.spec.style.expression

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.EXTENT
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.getTileCoordinates
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Helpers shared by the suites transcribed from `maplibre-style-spec`.
 *
 * They exist so a ported test reads as closely as possible to its upstream original: upstream
 * leans on `test/lib/geometry.ts` and on `vi.spyOn(console, 'warn')`, and these are the equivalents.
 */

internal val testJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Parses a JSON expression into the plain-Kotlin value model the parser works on. */
internal fun expr(source: String): Any? = jsonToValue(testJson.parseToJsonElement(source))

internal fun jsonValue(element: JsonElement): Any? = jsonToValue(element)

/**
 * Builds a feature from GeoJSON, converting its geometry into tile-local coordinates.
 *
 * Ported from `getGeometry` in `maplibre-style-spec/test/lib/geometry.ts`: coordinates are projected
 * with [getTileCoordinates] and then shifted to be relative to the tile, and `Multi*` geometries
 * collapse to their singular type. Pass `canonical = null` to get the geometry *type* without
 * decoding any coordinates, which is what upstream does when a fixture supplies no tile id.
 */
internal fun geoJsonFeature(
    geometryType: String? = null,
    coordinates: Any? = null,
    canonical: CanonicalTileId? = null,
    properties: Map<String, Any?> = emptyMap(),
    id: Any? = null,
): EvalFeature {
    val type = when (geometryType) {
        "MultiPoint" -> "Point"
        "MultiLineString" -> "LineString"
        "MultiPolygon" -> "Polygon"
        null -> "Unknown"
        else -> geometryType
    }

    val rings: List<List<Point2D>>? = if (geometryType != null && canonical != null) {
        when (geometryType) {
            "Point" -> listOf(listOf(tilePoint(coordinates, canonical)))
            "MultiPoint" -> asList(coordinates).map { listOf(tilePoint(it, canonical)) }
            "LineString" -> listOf(tileLine(coordinates, canonical))
            "MultiLineString", "Polygon" -> asList(coordinates).map { tileLine(it, canonical) }
            "MultiPolygon" -> asList(coordinates).flatMap { polygon ->
                asList(polygon).map { tileLine(it, canonical) }
            }

            else -> null
        }
    } else {
        null
    }

    return EvalFeature(
        type = type,
        id = id,
        properties = properties,
        geometryProvider = rings?.let { { it } },
    )
}

private fun asList(value: Any?): List<Any?> = value as? List<Any?> ?: emptyList()

private fun tilePoint(position: Any?, canonical: CanonicalTileId): Point2D {
    val p = asList(position)
    val coord = getTileCoordinates(
        listOf((p.getOrNull(0) as? Number)?.toDouble() ?: 0.0, (p.getOrNull(1) as? Number)?.toDouble() ?: 0.0),
        canonical,
    )
    // Shift so the point is relative to the tile rather than the world.
    return Point2D(coord[0] - canonical.x.toDouble() * EXTENT, coord[1] - canonical.y.toDouble() * EXTENT)
}

private fun tileLine(line: Any?, canonical: CanonicalTileId): List<Point2D> =
    asList(line).map { tilePoint(it, canonical) }

/**
 * Runs [block] with a collector installed on [ExpressionLogger], restoring the previous one after.
 *
 * The equivalent of upstream's `vi.spyOn(console, 'warn')`.
 */
internal fun <T> captureWarnings(block: (MutableList<String>) -> T): Pair<T, List<String>> {
    val warnings = mutableListOf<String>()
    val previous = ExpressionLogger.warn
    ExpressionLogger.warn = { warnings.add(it) }
    return try {
        block(warnings) to warnings.toList()
    } finally {
        ExpressionLogger.warn = previous
    }
}

/**
 * Compiles [expression] with root key `"rk"`, evaluates it against [properties], and returns the
 * single warning it produced.
 *
 * Ported from the `warnFor` helper in the `actionable warnings` blocks of
 * `maplibre-style-spec/src/expression/expression.test.ts`.
 */
internal fun warnFor(
    expression: Any?,
    properties: Map<String, Any?>,
    propertySpec: StylePropertySpec? = null,
    zoom: Double = 0.0,
): String {
    val compiled = createExpression(expression, "rk", propertySpec)
    assertTrue(compiled is ExpressionResult.Success, "expected a successful compile, got $compiled")

    val (_, warnings) = captureWarnings {
        compiled.value.evaluate(
            globals = GlobalProperties(zoom = zoom),
            feature = EvalFeature(type = "Point", properties = properties),
        )
    }
    if (warnings.size != 1) fail("expected exactly one warning, got $warnings")
    return warnings.single()
}

/** Asserts a compile succeeded and returns it, so ported tests can read like upstream's. */
internal fun assertCompiles(result: ExpressionResult<StyleExpression>): StyleExpression {
    assertTrue(result is ExpressionResult.Success, "expected a successful compile, got $result")
    return result.value
}

/** Asserts a property-expression compile succeeded and returns it. */
internal fun assertPropertyCompiles(
    result: ExpressionResult<StylePropertyExpression>,
): StylePropertyExpression {
    assertTrue(result is ExpressionResult.Success, "expected a successful compile, got $result")
    return result.value
}

/** Asserts a compile failed and returns the errors, formatted as upstream prints them. */
internal fun <T> assertCompileErrors(result: ExpressionResult<T>): List<ExpressionParsingError> {
    assertTrue(result is ExpressionResult.Error, "expected a compile error, got $result")
    return result.errors
}

internal fun assertErrorMessages(expected: List<String>, errors: List<ExpressionParsingError>) {
    assertEquals(expected, errors.map { it.message })
}
