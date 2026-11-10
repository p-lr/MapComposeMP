package ovh.plrapps.mapcompose.vector.spec.style.expression

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [StyleExpression] holds no state between evaluations.
 *
 * It used to keep the [EvaluationContext] it evaluated into, as upstream's
 * `StyleExpression._evaluator` does. That is a data race here, because one instance per style
 * property is shared by every tile worker -- see [StyleExpressionThreadSafetyTest] on desktop. These
 * cases pin the same property without threads, so every target runs them.
 */
class StyleExpressionStatelessTest {

    private fun compile(expression: Any?, spec: StylePropertySpec? = null): StyleExpression {
        val result = createExpression(expression, "layers[0].paint.line-color", spec)
        assertTrue(result is ExpressionResult.Success, "the fixture must compile")
        return result.value
    }

    @Test
    fun anEvaluationWithNoFeatureDoesNotSeeThePreviousFeature() {
        val expression = compile(listOf("get", "class"))

        assertEquals(
            "motorway",
            expression.evaluate(
                globals = GlobalProperties(zoom = 14.0),
                feature = EvalFeature(type = "LineString", properties = mapOf("class" to "motorway")),
            ),
        )
        assertNull(expression.evaluate(globals = GlobalProperties(zoom = 14.0)))
    }

    @Test
    fun anEvaluationWithNoZoomDoesNotSeeThePreviousZoom() {
        val expression = compile(
            listOf("step", listOf("zoom"), "low", 10.0, "high"),
            StylePropertySpec(expectedType = StringType),
        )

        assertEquals("high", expression.evaluate(globals = GlobalProperties(zoom = 14.0)))
        assertEquals("low", expression.evaluate(globals = GlobalProperties(zoom = 0.0)))
    }

    @Test
    fun interleavedEvaluationsDoNotAlias() {
        val expression = compile(listOf("get", "class"))
        val a = EvalFeature(type = "LineString", properties = mapOf("class" to "motorway"))
        val b = EvalFeature(type = "LineString", properties = mapOf("class" to "track"))

        repeat(50) {
            assertEquals("motorway", expression.evaluate(GlobalProperties(zoom = 14.0), a))
            assertEquals("track", expression.evaluate(GlobalProperties(zoom = 14.0), b))
        }
    }

    @Test
    fun anEvaluationContextIsIndependentOfEveryOther() {
        val first = EvaluationContext(
            globals = GlobalProperties(zoom = 3.0),
            feature = EvalFeature(type = "Point", properties = mapOf("a" to 1.0)),
        )
        val second = EvaluationContext(
            globals = GlobalProperties(zoom = 9.0),
            feature = EvalFeature(type = "LineString", properties = mapOf("a" to 2.0)),
        )

        assertEquals(3.0, first.globals?.zoom)
        assertEquals("Point", first.geometryType())
        assertEquals(1.0, first.properties()["a"])
        assertEquals(9.0, second.globals?.zoom)
        assertEquals("LineString", second.geometryType())
        assertEquals(2.0, second.properties()["a"])
    }
}
