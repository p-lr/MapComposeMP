package ovh.plrapps.mapcompose.vector.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderBudgetTest {

    @Test
    fun `a request that does not fit waits for a release`() = runTest {
        val budget = RenderBudget(budgetBytes = 100, maxConcurrent = 8)
        val firstHeld = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var secondRan = false

        launch { budget.withBudget(60) { firstHeld.complete(Unit); releaseFirst.await() } }
        firstHeld.await()
        val second = launch { budget.withBudget(60) { secondRan = true } }
        repeat(5) { yield() }
        assertFalse(secondRan)

        releaseFirst.complete(Unit)
        second.join()
        assertTrue(secondRan)
        assertEquals(0L to 0, budget.inUse())
    }

    @Test
    fun `requests that fit together run together`() = runTest {
        val budget = RenderBudget(budgetBytes = 100, maxConcurrent = 8)
        val bothHeld = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var held = 0

        val jobs = List(2) {
            launch {
                budget.withBudget(50) {
                    held += 1
                    if (held == 2) bothHeld.complete(Unit)
                    release.await()
                }
            }
        }
        bothHeld.await()
        assertEquals(100L to 2, budget.inUse())
        release.complete(Unit)
        jobs.forEach { it.join() }
    }

    @Test
    fun `a request larger than the whole budget is served alone`() = runTest {
        val budget = RenderBudget(budgetBytes = 100, maxConcurrent = 8)
        var ran = false
        budget.withBudget(1_000) { ran = true }
        assertTrue(ran)
    }

    @Test
    fun `the concurrency cap holds even when bytes would fit`() = runTest {
        val budget = RenderBudget(budgetBytes = 1_000, maxConcurrent = 1)
        val firstHeld = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var secondRan = false

        launch { budget.withBudget(1) { firstHeld.complete(Unit); releaseFirst.await() } }
        firstHeld.await()
        val second = launch { budget.withBudget(1) { secondRan = true } }
        repeat(5) { yield() }
        assertFalse(secondRan)
        releaseFirst.complete(Unit)
        second.join()
        assertTrue(secondRan)
    }

    @Test
    fun `a failing block releases what it held`() = runTest {
        val budget = RenderBudget(budgetBytes = 100, maxConcurrent = 8)
        runCatching { budget.withBudget(80) { error("boom") } }
        assertEquals(0L to 0, budget.inUse())
    }
}
