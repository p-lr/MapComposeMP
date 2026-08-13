package ovh.plrapps.mapcompose.vector.spec.style.expression

/**
 * Converts a legacy (v7) *function object* — `{stops}`, `{type}`, `{property}`, `{base}` — into an
 * equivalent v8 expression.
 *
 * Ported from `maplibre-style-spec/src/function/convert.ts`. Values here are already plain Kotlin
 * (see [jsonToValue]), and the output is plain Kotlin too, ready for [createExpression].
 *
 * Token strings (`"{name}"`) are only converted when [StylePropertySpec.tokens] is set, which
 * MapCompose leaves off: `SymbolLayerPainter` performs token substitution itself, so converting
 * here as well would double-apply it.
 */
fun convertLegacyFunction(parameters: Map<String, Any?>, propertySpec: StylePropertySpec): Any? {
    val rawStops = parameters["stops"] as? List<*>
        ?: return convertIdentityFunction(parameters, propertySpec)

    val zoomAndFeatureDependent = (rawStops.firstOrNull() as? List<*>)?.firstOrNull() is Map<*, *>
    val featureDependent = zoomAndFeatureDependent || parameters.containsKey("property")
    val zoomDependent = zoomAndFeatureDependent || !featureDependent

    val stops = rawStops.mapNotNull { stop ->
        val pair = stop as? List<*> ?: return@mapNotNull null
        if (pair.size != 2) return@mapNotNull null
        val output = pair[1]
        if (!featureDependent && propertySpec.tokens && output is String) {
            pair[0] to convertTokenString(output)
        } else {
            pair[0] to convertLiteral(output)
        }
    }

    return when {
        zoomAndFeatureDependent -> convertZoomAndPropertyFunction(parameters, propertySpec, stops)
        zoomDependent -> convertZoomFunction(parameters, propertySpec, stops)
        else -> convertPropertyFunction(parameters, propertySpec, stops)
    }
}

private fun convertLiteral(value: Any?): Any? =
    if (value is Map<*, *> || value is List<*>) listOf("literal", value) else value

private fun convertIdentityFunction(parameters: Map<String, Any?>, propertySpec: StylePropertySpec): Any? {
    val property = parameters["property"] ?: return null
    val get = listOf("get", property)

    if (!parameters.containsKey("default")) {
        // Expressions for string-valued properties get coerced by default; to preserve legacy
        // function semantics, insert an explicit assertion instead.
        return if (propertySpec.expectedType == StringType) listOf("string", get) else get
    }

    val default = parameters["default"]
    propertySpec.enumValues?.let { values ->
        return listOf("match", get, values.toList(), get, default)
    }

    val assertion = when (val t = propertySpec.expectedType) {
        ColorType -> "to-color"
        StringType -> "string"
        NumberType -> "number"
        BooleanType -> "boolean"
        is ArrayType -> "array"
        else -> return listOf("coalesce", get, convertLiteral(default))
    }

    return if (propertySpec.expectedType is ArrayType) {
        val itemType = (propertySpec.expectedType as ArrayType).itemType
        listOf(assertion, itemType.kind, (propertySpec.expectedType as ArrayType).n, get, convertLiteral(default))
    } else {
        listOf(assertion, get, convertLiteral(default))
    }
}

private fun interpolateOperator(parameters: Map<String, Any?>): String = when (parameters["colorSpace"]) {
    "hcl" -> "interpolate-hcl"
    "lab" -> "interpolate-lab"
    else -> "interpolate"
}

private fun functionType(parameters: Map<String, Any?>, propertySpec: StylePropertySpec): String =
    parameters["type"] as? String ?: if (propertySpec.supportsInterpolation) "exponential" else "interval"

/**
 * The value a `categorical` / `case` conversion falls back to when the feature matches no stop.
 *
 * Upstream uses `parameters.default ?: propertySpec.default`, and every real MapLibre property has
 * a default in the generated style-spec metadata. MapCompose models properties as Kotlin data
 * classes and has no such table, so when neither is available a type-appropriate neutral literal is
 * substituted: without one the converted `match` would carry a `null` output and fail to type-check
 * against the property's type, taking the whole property down with it.
 */
private fun fallback(parameters: Map<String, Any?>, propertySpec: StylePropertySpec): Any? {
    if (parameters.containsKey("default")) return convertLiteral(parameters["default"])
    if (propertySpec.defaultValue != null) return convertLiteral(propertySpec.defaultValue)
    return neutralDefaultFor(propertySpec.expectedType)
}

private fun neutralDefaultFor(type: ExprType?): Any? = when (type) {
    ColorType -> "rgba(0,0,0,0)"
    NumberType -> 0.0
    StringType -> ""
    BooleanType -> false
    is ArrayType -> listOf("literal", emptyList<Any?>())
    else -> null
}

private fun convertPropertyFunction(
    parameters: Map<String, Any?>,
    propertySpec: StylePropertySpec,
    stops: List<Pair<Any?, Any?>>,
): Any? {
    val type = functionType(parameters, propertySpec)
    val get = listOf("get", parameters["property"])

    return when {
        type == "categorical" && stops.firstOrNull()?.first is Boolean -> {
            val expression = mutableListOf<Any?>("case")
            for ((input, output) in stops) {
                expression.add(listOf("==", get, input))
                expression.add(output)
            }
            expression.add(fallback(parameters, propertySpec))
            expression
        }

        type == "categorical" -> {
            val expression = mutableListOf<Any?>("match", get)
            for ((input, output) in stops) appendStopPair(expression, input, output, isStep = false)
            expression.add(fallback(parameters, propertySpec))
            expression
        }

        type == "interval" -> {
            val expression = mutableListOf<Any?>("step", listOf("number", get))
            for ((input, output) in stops) appendStopPair(expression, input, output, isStep = true)
            fixupDegenerateStepCurve(expression)
            if (!parameters.containsKey("default")) {
                expression
            } else {
                listOf(
                    "case",
                    listOf("==", listOf("typeof", get), "number"),
                    expression,
                    convertLiteral(parameters["default"]),
                )
            }
        }

        type == "exponential" -> {
            val base = (parameters["base"] as? Number)?.toDouble() ?: 1.0
            val expression = mutableListOf<Any?>(
                interpolateOperator(parameters),
                if (base == 1.0) listOf("linear") else listOf("exponential", base),
                listOf("number", get),
            )
            for ((input, output) in stops) appendStopPair(expression, input, output, isStep = false)
            if (!parameters.containsKey("default")) {
                expression
            } else {
                listOf(
                    "case",
                    listOf("==", listOf("typeof", get), "number"),
                    expression,
                    convertLiteral(parameters["default"]),
                )
            }
        }

        else -> null
    }
}

private fun convertZoomFunction(
    parameters: Map<String, Any?>,
    propertySpec: StylePropertySpec,
    stops: List<Pair<Any?, Any?>>,
    input: Any? = listOf("zoom"),
): Any? {
    val type = functionType(parameters, propertySpec)
    val expression: MutableList<Any?>
    val isStep: Boolean

    when (type) {
        "interval" -> {
            expression = mutableListOf("step", input)
            isStep = true
        }

        "exponential" -> {
            val base = (parameters["base"] as? Number)?.toDouble() ?: 1.0
            expression = mutableListOf(
                interpolateOperator(parameters),
                if (base == 1.0) listOf("linear") else listOf("exponential", base),
                input,
            )
            isStep = false
        }

        else -> return null
    }

    for ((stopInput, output) in stops) appendStopPair(expression, stopInput, output, isStep)
    fixupDegenerateStepCurve(expression)
    return expression
}

private fun convertZoomAndPropertyFunction(
    parameters: Map<String, Any?>,
    propertySpec: StylePropertySpec,
    stops: List<Pair<Any?, Any?>>,
): Any? {
    val featureFunctionParameters = linkedMapOf<Double, Map<String, Any?>>()
    val featureFunctionStops = linkedMapOf<Double, MutableList<Pair<Any?, Any?>>>()
    val zoomStops = mutableListOf<Double>()

    for ((rawInput, output) in stops) {
        val stopObject = rawInput as? Map<*, *> ?: continue
        val zoom = (stopObject["zoom"] as? Number)?.toDouble() ?: continue
        if (zoom !in featureFunctionParameters) {
            featureFunctionParameters[zoom] = buildMap {
                put("zoom", zoom)
                if (parameters.containsKey("type")) put("type", parameters["type"])
                if (parameters.containsKey("property")) put("property", parameters["property"])
                if (parameters.containsKey("default")) put("default", parameters["default"])
            }
            featureFunctionStops[zoom] = mutableListOf()
            zoomStops.add(zoom)
        }
        featureFunctionStops.getValue(zoom).add(stopObject["value"] to output)
    }

    // The interpolation type for the zoom dimension of a zoom-and-property function comes from the
    // property spec: linear for interpolatable properties, step otherwise.
    return if (propertySpec.supportsInterpolation) {
        val expression = mutableListOf<Any?>(interpolateOperator(parameters), listOf("linear"), listOf("zoom"))
        for (z in zoomStops) {
            val output = convertPropertyFunction(
                featureFunctionParameters.getValue(z), propertySpec, featureFunctionStops.getValue(z),
            )
            appendStopPair(expression, z, output, isStep = false)
        }
        expression
    } else {
        val expression = mutableListOf<Any?>("step", listOf("zoom"))
        for (z in zoomStops) {
            val output = convertPropertyFunction(
                featureFunctionParameters.getValue(z), propertySpec, featureFunctionStops.getValue(z),
            )
            appendStopPair(expression, z, output, isStep = true)
        }
        fixupDegenerateStepCurve(expression)
        expression
    }
}

/**
 * A degenerate step curve (a constant function) needs a no-op stop to be a valid expression.
 *
 * **Deliberate divergence.** Upstream reads `expression[3]` *after* pushing `0`, so it appends the
 * `0` it just pushed and turns every constant legacy function into `0` above zoom 0. This repeats
 * the intended output instead, keeping the function constant.
 */
private fun fixupDegenerateStepCurve(expression: MutableList<Any?>) {
    if (expression.firstOrNull() == "step" && expression.size == 3) {
        expression.add(0.0)
        expression.add(expression[2])
    }
}

private fun appendStopPair(curve: MutableList<Any?>, input: Any?, output: Any?, isStep: Boolean) {
    // Skip duplicate stop values: functions never validated them, but expressions do.
    if (curve.size > 3 && input == curve[curve.size - 2]) return
    // Step curves omit the first input value, which is redundant.
    if (!(isStep && curve.size == 2)) curve.add(input)
    curve.add(output)
}

/**
 * `"String with {name} token"` becomes `["concat", "String with ", ["get", "name"], " token"]`.
 *
 * Ported from `convertTokenString` in `maplibre-style-spec/src/function/convert.ts`.
 */
fun convertTokenString(s: String): Any? {
    val result = mutableListOf<Any?>("concat")
    val regex = Regex("\\{([^{}]+)\\}")
    var pos = 0
    for (match in regex.findAll(s)) {
        val literal = s.substring(pos, match.range.first)
        pos = match.range.last + 1
        if (literal.isNotEmpty()) result.add(literal)
        result.add(listOf("get", match.groupValues[1]))
    }

    if (result.size == 1) return s
    if (pos < s.length) {
        result.add(s.substring(pos))
    } else if (result.size == 2) {
        return listOf("to-string", result[1])
    }

    return result
}
