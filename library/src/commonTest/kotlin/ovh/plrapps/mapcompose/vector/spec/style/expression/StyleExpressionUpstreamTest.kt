package ovh.plrapps.mapcompose.vector.spec.style.expression

import ovh.plrapps.mapcompose.vector.spec.style.utils.ColorParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Transcribed from `maplibre-style-spec/src/expression/expression.test.ts`, plus the one applicable
 * case from `src/expression/index.test.ts`.
 *
 * These assert what the JSON conformance fixtures structurally cannot: the exact warning text, the
 * parser index path a runtime failure carries, and the fact that each throw site warns only once.
 *
 * Not transcribed, and why:
 * - `v8.json includes all definitions from style-spec` — compares the operator registry against
 *   upstream's generated spec metadata, which MapCompose has no equivalent of.
 * - the `variableAnchorOffsetCollection`, `padding`, `numberArray` and `projectionDefinition`
 *   cases — property types this port does not model; see the KDoc on `ExprType`.
 * - `a legacy stop function throw warns …` — targets `StylePropertyFunction`, the `createFunction`
 *   wrapper, which was not ported.
 * - 17 of the 18 `index.test.ts` cases — they exercise `normalizePropertyExpression` over those
 *   same unmodelled types.
 */
class StyleExpressionUpstreamTest {

    private fun numberSpec(default: Any? = 42.0, interpolated: Boolean = false) = StylePropertySpec(
        expectedType = NumberType,
        defaultValue = default,
        supportsInterpolation = interpolated,
    )

    private fun feature(vararg properties: Pair<String, Any?>) =
        EvalFeature(type = "Point", properties = properties.toMap())

    // region createPropertyExpression

    @Test
    fun `prohibits non-interpolable properties from using an interpolate expression`() {
        val result = createPropertyExpression(
            expr("""["interpolate",["linear"],["zoom"],0,0,10,10]"""),
            "layers[0].paint.line-width",
            StylePropertySpec(
                expectedType = NumberType,
                supportsPropertyExpression = false,
                supportsInterpolation = false,
            ),
        )
        val errors = assertCompileErrors(result)
        assertEquals(1, errors.size)
        assertEquals("\"interpolate\" expressions cannot be used with this property", errors[0].message)
    }

    @Test
    fun `sets globalStateRefs`() {
        val compiled = assertPropertyCompiles(
            createPropertyExpression(
                expr("""["case",[">",["global-state","stateKey"],0],100,["global-state","anotherStateKey"]]"""),
                "layers[0].paint.line-width",
                numberSpec(default = null),
            )
        )
        assertEquals(setOf("stateKey", "anotherStateKey"), compiled.globalStateRefs)
    }

    // endregion

    // region evaluate expression

    @Test
    fun `silently falls back to default for nullish values`() {
        val compiled = assertPropertyCompiles(
            createPropertyExpression(
                expr("""["global-state","x"]"""),
                "layers[0].paint.line-width",
                StylePropertySpec(expectedType = null, defaultValue = 42.0),
            )
        )

        val (present, presentWarnings) = captureWarnings {
            compiled.styleExpression.evaluate(GlobalProperties(zoom = 10.0, globalState = mapOf("x" to 5.0)))
        }
        assertEquals(5.0, present)
        assertTrue(presentWarnings.isEmpty())

        val (absent, absentWarnings) = captureWarnings {
            compiled.styleExpression.evaluate(GlobalProperties(zoom = 10.0, globalState = emptyMap()))
        }
        assertEquals(42.0, absent)
        assertTrue(absentWarnings.isEmpty())
    }

    @Test
    fun `global state as expression property`() {
        val compiled = assertPropertyCompiles(
            createPropertyExpression(
                expr("""["global-state","x"]"""),
                "layers[0].paint.line-width",
                StylePropertySpec(expectedType = null, defaultValue = 42.0),
                globalState = mapOf("x" to 5.0),
            )
        )

        // The state given at construction wins over whatever the render pass supplies.
        val (result, warnings) = captureWarnings {
            compiled.styleExpression.evaluate(GlobalProperties(zoom = 10.0, globalState = mapOf("x" to 15.0)))
        }
        assertEquals(5.0, result)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `global state as expression property of zoom dependent expression`() {
        val compiled = assertPropertyCompiles(
            createPropertyExpression(
                expr("""["interpolate",["linear"],["zoom"],10,["global-state","x"],20,50]"""),
                "layers[0].paint.line-width",
                numberSpec(interpolated = true),
                globalState = mapOf("x" to 5.0),
            )
        )

        val (result, warnings) = captureWarnings {
            compiled.styleExpression.evaluate(GlobalProperties(zoom = 10.0, globalState = mapOf("x" to 15.0)))
        }
        assertEquals(5.0, result)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a failing assertion warns with its property location and the fallback value`() {
        val compiled = assertPropertyCompiles(
            createPropertyExpression(
                expr("""["number",["get","x"]]"""),
                "layers[0].paint.line-width",
                numberSpec(),
            )
        )

        val (result, warnings) = captureWarnings {
            compiled.styleExpression.evaluate(GlobalProperties(zoom = 0.0), feature())
        }
        assertEquals(42.0, result)
        assertEquals(
            listOf(
                "layers[0].paint.line-width: Expected value to be of type number, but found null instead. " +
                        "Falling back to 42."
            ),
            warnings,
        )
    }

    @Test
    fun `an empty rootKey throws at construction because not actionable`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            createPropertyExpression(expr("""["number",["get","x"]]"""), "", numberSpec())
        }
        assertTrue(
            failure.message!!.startsWith(
                "rootKey must identify the location of the expression in the style JSON"
            ),
            "unexpected message: ${failure.message}",
        )
    }

    @Test
    fun `a nested failure appends its parser index path after the rootKey`() {
        val compiled = assertPropertyCompiles(
            createPropertyExpression(
                expr("""["case",false,["number",["get","a"]],["number",["get","b"]]]"""),
                "layers[0].paint.line-width",
                numberSpec(),
            )
        )

        val (result, warnings) = captureWarnings {
            compiled.styleExpression.evaluate(GlobalProperties(zoom = 0.0), feature())
        }
        assertEquals(42.0, result)
        assertEquals(
            listOf(
                "layers[0].paint.line-width[3]: Expected value to be of type number, but found null instead. " +
                        "Falling back to 42."
            ),
            warnings,
        )
    }

    @Test
    fun `sibling throw sites each warn once deduped by path and message`() {
        val compiled = assertPropertyCompiles(
            createPropertyExpression(
                expr("""["case",["==",["get","t"],1],["number",["get","a"]],["number",["get","b"]]]"""),
                "layers[0].paint.line-width",
                numberSpec(),
            )
        )

        val (_, warnings) = captureWarnings {
            // Trigger the first arm (index 2), then the default arm (index 3).
            assertEquals(
                42.0,
                compiled.styleExpression.evaluate(GlobalProperties(zoom = 0.0), feature("t" to 1.0)),
            )
            assertEquals(
                42.0,
                compiled.styleExpression.evaluate(GlobalProperties(zoom = 0.0), feature("t" to 2.0)),
            )
        }

        assertEquals(2, warnings.size)
        assertTrue(
            warnings.contains(
                "layers[0].paint.line-width[2]: Expected value to be of type number, but found null instead. " +
                        "Falling back to 42."
            )
        )
        assertTrue(
            warnings.contains(
                "layers[0].paint.line-width[3]: Expected value to be of type number, but found null instead. " +
                        "Falling back to 42."
            )
        )
    }

    @Test
    fun `an auto-wrapped assertion carries its nested index path`() {
        // Bare ["get"] arms get implicitly wrapped in an assertion by the parser.
        val compiled = assertPropertyCompiles(
            createPropertyExpression(
                expr("""["case",false,["get","a"],["get","b"]]"""),
                "layers[0].paint.line-width",
                numberSpec(),
            )
        )

        val (result, warnings) = captureWarnings {
            compiled.styleExpression.evaluate(GlobalProperties(zoom = 0.0), feature())
        }
        assertEquals(42.0, result)
        assertEquals(
            listOf(
                "layers[0].paint.line-width[3]: Expected value to be of type number, but found null instead. " +
                        "Falling back to 42."
            ),
            warnings,
        )
    }

    @Test
    fun `warns and falls back to default for invalid enum values`() {
        val compiled = assertPropertyCompiles(
            createPropertyExpression(
                expr("""["get","x"]"""),
                "layers[0].layout.text-justify",
                StylePropertySpec(
                    expectedType = StringType,
                    defaultValue = "a",
                    enumValues = linkedSetOf("a", "b", "c"),
                ),
            )
        )

        assertEquals(EvaluationKind.SOURCE, compiled.kind)

        val (_, warnings) = captureWarnings {
            assertEquals(
                "b",
                compiled.styleExpression.evaluate(GlobalProperties(zoom = 0.0), feature("x" to "b")),
            )
            assertEquals(
                "a",
                compiled.styleExpression.evaluate(GlobalProperties(zoom = 0.0), feature("x" to "invalid")),
            )
        }
        assertEquals(
            listOf(
                "layers[0].layout.text-justify: Expected value to be one of \"a\", \"b\", \"c\", " +
                        "but found \"invalid\" instead. Falling back to a."
            ),
            warnings,
        )
    }

    // endregion

    // region actionable warnings: runtime throw sites carry their index path

    @Test
    fun `at with an out-of-bounds index`() {
        assertEquals(
            "rk[1]: Array index out of bounds: 5 > 2.",
            warnFor(expr("""["typeof",["at",["get","i"],["literal",[1,2,3]]]]"""), mapOf("i" to 5.0)),
        )
    }

    @Test
    fun `length of a non-array or string value`() {
        assertEquals(
            "rk[1]: Expected value to be of type string or array, but found number instead.",
            warnFor(expr("""["typeof",["length",["get","x"]]]"""), mapOf("x" to 5.0)),
        )
    }

    @Test
    fun `index-of in a non-array or string haystack`() {
        assertEquals(
            "rk[1]: Expected second argument to be of type array or string, but found number instead.",
            warnFor(
                expr("""["typeof",["index-of",["get","n"],["get","h"]]]"""),
                mapOf("n" to "a", "h" to 5.0),
            ),
        )
    }

    @Test
    fun `slice of a non-array or string value`() {
        assertEquals(
            "rk[1]: Expected first argument to be of type array or string, but found number instead.",
            warnFor(expr("""["typeof",["slice",["get","x"],0]]"""), mapOf("x" to 5.0)),
        )
    }

    @Test
    fun `in with a non-array or string haystack`() {
        assertEquals(
            "rk[1]: Expected second argument to be of type array or string, but found number instead.",
            warnFor(expr("""["typeof",["in",["get","n"],["get","h"]]]"""), mapOf("n" to "a", "h" to 5.0)),
        )
    }

    @Test
    fun `to-color of an unparseable value`() {
        assertEquals(
            "rk[1]: Could not parse color from value 'notacolor'",
            warnFor(expr("""["typeof",["to-color",["get","c"]]]"""), mapOf("c" to "notacolor")),
        )
    }

    @Test
    fun `ordering comparison of mismatched types`() {
        assertEquals(
            "rk[1]: Expected arguments for \">\" to be (string, string) or (number, number), " +
                    "but found (string, number) instead.",
            warnFor(expr("""["typeof",[">",["get","a"],["get","b"]]]"""), mapOf("a" to "x", "b" to 5.0)),
        )
    }

    @Test
    fun `user-emitted error is prefixed with the rootKey`() {
        // Compound expressions don't carry a parser sub-path, so the message is anchored at the
        // property root only.
        assertEquals("rk: boom", warnFor(expr("""["error","boom"]"""), emptyMap()))
    }

    // endregion

    // region actionable warnings: fallback rendering

    @Test
    fun `color default renders as rgba`() {
        assertEquals(
            "rk: Could not parse color from value 'notacolor' Falling back to rgba(255,0,0,1).",
            warnFor(
                expr("""["to-color",["get","c"]]"""),
                mapOf("c" to "notacolor"),
                propertySpec = StylePropertySpec(
                    expectedType = ColorType,
                    defaultValue = ColorParser.parseColorString("red"),
                ),
            ),
        )
    }

    @Test
    fun `null default omits the fallback suffix entirely`() {
        assertEquals(
            "rk: Expected value to be of type number, but found null instead.",
            warnFor(
                expr("""["number",["get","x"]]"""),
                emptyMap(),
                propertySpec = StylePropertySpec(expectedType = NumberType),
            ),
        )
    }

    // endregion

    // region nonexistent operators and semiliteral

    @Test
    fun `ExpressionSpecification operator does not exist`() {
        val errors = assertCompileErrors(
            createExpression(expr("""["ExpressionSpecification"]"""), "layers[0].filter")
        )
        assertTrue(
            errors[0].message.contains("Unknown expression \"ExpressionSpecification\"."),
            "unexpected message: ${errors[0].message}",
        )
    }

    @Test
    fun `semiliteral gives informative error for non-JSON values`() {
        val notAValue: () -> Unit = {}
        val errors = assertCompileErrors(
            createExpression(listOf("semiliteral", notAValue), "layers[0].paint.line-width")
        )
        assertEquals(1, errors.size)
        assertEquals("invalid value of type \"function\"", errors[0].message)
    }

    // endregion

    /**
     * From `index.test.ts`. Upstream must strip unknown keys off the globals object when it splices
     * in the global state; here `GlobalProperties` is a data class, so stray fields cannot exist and
     * the test reduces to asserting that the known fields survive and the state is replaced.
     */
    @Test
    fun `adding global state keeps the known globals and replaces the state`() {
        val compiled = assertCompiles(
            createExpression(
                expr("""["global-state","x"]"""),
                "layers[0].paint.line-width",
                StylePropertySpec(expectedType = null, defaultValue = 42.0),
                globalState = mapOf("x" to 5.0),
            )
        )

        var seen: GlobalProperties? = null
        val probe = object : Expression {
            override val type = ValueType
            override fun evaluate(ctx: EvaluationContext): Any? {
                seen = ctx.globals
                return null
            }

            override fun eachChild(fn: (Expression) -> Unit) = Unit
            override fun outputDefined() = false
        }

        StyleExpression(probe, "layers[0].paint.line-width", null, mapOf("x" to 5.0))
            .evaluate(GlobalProperties(zoom = 10.0, heatmapDensity = 0.25))

        assertEquals(10.0, seen!!.zoom)
        assertEquals(0.25, seen!!.heatmapDensity)
        assertEquals(mapOf("x" to 5.0), seen!!.globalState)

        // And the compiled expression reads that same state rather than the caller's.
        assertEquals(5.0, compiled.evaluate(GlobalProperties(zoom = 0.0, globalState = mapOf("x" to 99.0))))
    }
}
