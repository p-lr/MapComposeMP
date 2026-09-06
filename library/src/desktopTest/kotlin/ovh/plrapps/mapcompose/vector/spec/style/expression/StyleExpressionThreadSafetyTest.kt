package ovh.plrapps.mapcompose.vector.spec.style.expression

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import ovh.plrapps.mapcompose.vector.spec.style.filter.featureFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One [StyleExpression] per style property is shared by the whole tile-worker pool
 * (`core/TileCollector.kt` runs `workerCount` coroutines on a limited-parallelism dispatcher) and by
 * the symbol layout pass. It used to hold the `EvaluationContext` it evaluated into, as upstream's
 * `StyleExpression._evaluator` does -- correct there because JS has one thread, a data race here.
 *
 * The visible symptom was a road drawn black: a torn read makes the tree read another feature's
 * properties, the type error is caught by [StyleExpression.evaluate], the property falls back to its
 * spec default, and `line-color`'s default is `Color.Black` -- or the layer's *filter* returns
 * `false` and only the dark casing is drawn.
 *
 * Desktop only: `wasmJs` has no threads, so the race cannot be provoked there.
 */
class StyleExpressionThreadSafetyTest {

    private val paintProperty = listOf(
        "match",
        listOf("get", "class"),
        "motorway", "yellow",
        "track", "grey",
        "white",
    )

    private val widthProperty = listOf(
        "interpolate",
        listOf("exponential", 2.0),
        listOf("zoom"),
        10.0, listOf("get", "narrow"),
        16.0, listOf("get", "wide"),
    )

    private fun featureFor(index: Int): EvalFeature {
        val cls = CLASSES[index % CLASSES.size]
        return EvalFeature(
            type = "LineString",
            properties = mapOf(
                "class" to cls,
                "narrow" to (index + 1).toDouble(),
                "wide" to ((index + 1) * 10).toDouble(),
            ),
        )
    }

    private fun expectedColor(index: Int): String = when (CLASSES[index % CLASSES.size]) {
        "motorway" -> "yellow"
        "track" -> "grey"
        else -> "white"
    }

    @Test
    fun aSharedStyleExpressionIsSafeUnderConcurrentEvaluation() = runBlocking {
        val compiled = createExpression(paintProperty, "layers[0].paint.line-color", STRING_SPEC)
        assertTrue(compiled is ExpressionResult.Success, "the fixture must compile")
        val expression = compiled.value

        val failures = withContext(Dispatchers.Default) {
            (0 until WORKERS).map { worker ->
                async {
                    var bad = 0
                    repeat(ITERATIONS) {
                        val value = expression.evaluate(
                            globals = GlobalProperties(zoom = 14.0),
                            feature = featureFor(worker),
                        )
                        if (value != expectedColor(worker)) bad += 1
                    }
                    bad
                }
            }.awaitAll().sum()
        }

        assertEquals(0, failures, "an evaluation saw another worker's feature")
    }

    @Test
    fun aSharedStyleExpressionKeepsEachCallersZoom() = runBlocking {
        val compiled = createExpression(widthProperty, "layers[0].paint.line-width", NUMBER_SPEC)
        assertTrue(compiled is ExpressionResult.Success, "the fixture must compile")
        val expression = compiled.value

        /* Every worker pins its own zoom to a stop, so the answer is that stop's output exactly and
         * a leaked `globals` from another worker is a different number, not a rounding difference. */
        val failures = withContext(Dispatchers.Default) {
            (0 until WORKERS).map { worker ->
                async {
                    val zoom = if (worker % 2 == 0) 10.0 else 16.0
                    val expected = if (worker % 2 == 0) (worker + 1).toDouble() else ((worker + 1) * 10).toDouble()
                    var bad = 0
                    repeat(ITERATIONS) {
                        val value = expression.evaluate(
                            globals = GlobalProperties(zoom = zoom),
                            feature = featureFor(worker),
                        )
                        if (value != expected) bad += 1
                    }
                    bad
                }
            }.awaitAll().sum()
        }

        assertEquals(0, failures, "an evaluation saw another worker's zoom or feature")
    }

    @Test
    fun aSharedFilterIsSafeUnderConcurrentEvaluation() = runBlocking {
        val compiled = featureFilter(
            listOf("==", listOf("get", "class"), "motorway"),
            "layers[0].filter",
        )
        assertTrue(compiled is ExpressionResult.Success, "the fixture must compile")
        val filter = compiled.value

        val failures = withContext(Dispatchers.Default) {
            (0 until WORKERS).map { worker ->
                async {
                    val expected = CLASSES[worker % CLASSES.size] == "motorway"
                    var bad = 0
                    repeat(ITERATIONS) {
                        val kept = filter?.filter(
                            globals = GlobalProperties(zoom = 14.0),
                            feature = featureFor(worker),
                        ) ?: true
                        if (kept != expected) bad += 1
                    }
                    bad
                }
            }.awaitAll().sum()
        }

        assertEquals(0, failures, "a filter decision was taken against another worker's feature")
    }

    private companion object {
        const val WORKERS = 8
        const val ITERATIONS = 20_000

        val CLASSES = listOf("motorway", "track", "minor", "service")

        val STRING_SPEC = StylePropertySpec(expectedType = StringType)
        val NUMBER_SPEC = StylePropertySpec(expectedType = NumberType)
    }
}
