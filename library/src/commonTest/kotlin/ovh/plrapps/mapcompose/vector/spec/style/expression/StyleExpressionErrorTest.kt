package ovh.plrapps.mapcompose.vector.spec.style.expression

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The error policy: parsing collects errors instead of throwing, and evaluation falls back to the
 * property default with a warning logged once per distinct failure.
 */
class StyleExpressionErrorTest {

    private val warnings = mutableListOf<String>()
    private val originalWarn = ExpressionLogger.warn

    private fun captureWarnings() {
        warnings.clear()
        ExpressionLogger.warn = { warnings.add(it) }
    }

    @AfterTest
    fun restoreLogger() {
        ExpressionLogger.warn = originalWarn
    }

    @Test
    fun anUnknownOperatorIsAParseErrorNotAThrow() {
        val result = createExpression(listOf("nope", 1.0), "layers[0].paint.line-width")
        assertTrue(result is ExpressionResult.Error)
        assertEquals(1, result.errors.size)
        assertTrue(
            result.errors[0].message.startsWith("Unknown expression \"nope\""),
            "unexpected message: ${result.errors[0].message}",
        )
    }

    @Test
    fun aMalformedExpressionIsAParseErrorNotAThrow() {
        val result = createExpression(listOf("get"), "layers[0].paint.line-width")
        assertTrue(result is ExpressionResult.Error)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun aRuntimeErrorFallsBackToThePropertyDefault() {
        captureWarnings()
        val spec = StylePropertySpec(expectedType = NumberType, defaultValue = 1.0)
        val compiled = createExpression(listOf("number", listOf("get", "x")), "layers[0].paint.line-width", spec)
        assertTrue(compiled is ExpressionResult.Success)

        val value = compiled.value.evaluate(
            globals = GlobalProperties(zoom = 0.0),
            feature = EvalFeature(type = "Point", properties = mapOf("x" to "not a number")),
        )
        assertEquals(1.0, value)
        assertEquals(1, warnings.size)
        assertTrue("layers[0].paint.line-width" in warnings[0], "missing root key: ${warnings[0]}")
        assertTrue("Falling back to 1" in warnings[0], "missing fallback note: ${warnings[0]}")
    }

    @Test
    fun repeatedIdenticalRuntimeErrorsWarnOnlyOnce() {
        captureWarnings()
        val spec = StylePropertySpec(expectedType = NumberType, defaultValue = 1.0)
        val compiled = createExpression(listOf("number", listOf("get", "x")), "layers[0].paint.line-width", spec)
        assertTrue(compiled is ExpressionResult.Success)

        val feature = EvalFeature(type = "Point", properties = mapOf("x" to "not a number"))
        repeat(50) {
            compiled.value.evaluate(globals = GlobalProperties(zoom = 0.0), feature = feature)
        }
        assertEquals(1, warnings.size)
    }

    @Test
    fun withoutADefaultTheFallbackIsNullSoCallersCanSupplyTheirOwn() {
        captureWarnings()
        val spec = StylePropertySpec(expectedType = NumberType)
        val compiled = createExpression(listOf("number", listOf("get", "x")), "layers[0].paint.line-width", spec)
        assertTrue(compiled is ExpressionResult.Success)

        assertNull(
            compiled.value.evaluate(
                globals = GlobalProperties(zoom = 0.0),
                feature = EvalFeature(type = "Point", properties = mapOf("x" to "not a number")),
            )
        )
    }

    @Test
    fun evaluateWithoutErrorHandlingStillThrows() {
        val spec = StylePropertySpec(expectedType = NumberType, defaultValue = 1.0)
        val compiled = createExpression(listOf("number", listOf("get", "x")), "layers[0].paint.line-width", spec)
        assertTrue(compiled is ExpressionResult.Success)

        var threw = false
        try {
            compiled.value.evaluateWithoutErrorHandling(
                globals = GlobalProperties(zoom = 0.0),
                feature = EvalFeature(type = "Point", properties = mapOf("x" to "not a number")),
            )
        } catch (e: RuntimeError) {
            threw = true
        }
        assertTrue(threw, "evaluateWithoutErrorHandling should propagate the error")
    }
}
