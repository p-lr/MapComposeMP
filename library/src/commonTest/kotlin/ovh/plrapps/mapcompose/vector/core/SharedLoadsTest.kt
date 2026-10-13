package ovh.plrapps.mapcompose.vector.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SharedLoadsTest {

    private fun TestScope.loads() = SharedLoads<String>(
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
    )

    @Test
    fun `concurrent callers for one key share one load`() = runTest {
        val loads = loads()
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val callers = List(5) {
            async { loads.load("k") { runs++; gate.await(); "bytes" } }
        }
        repeat(5) { yield() }
        gate.complete(Unit)
        assertEquals(List(5) { "bytes" }, callers.map { it.await() })
        assertEquals(1, runs)
    }

    @Test
    fun `a caller that goes away does not cancel the load for the others`() = runTest {
        val loads = loads()
        val gate = CompletableDeferred<Unit>()
        val first = launch { loads.load("k") { gate.await(); "bytes" } }
        val second = async { loads.load("k") { error("not run twice") } }
        repeat(5) { yield() }
        first.cancel()
        gate.complete(Unit)
        assertEquals("bytes", second.await())
    }

    @Test
    fun `a failure reaches every waiter and the next load starts afresh`() = runTest {
        val loads = loads()
        assertFailsWith<IllegalStateException> { loads.load("k") { error("network down") } }
        assertEquals("bytes", loads.load("k") { "bytes" })
    }

    @Test
    fun `different keys load independently`() = runTest {
        val loads = loads()
        assertEquals("a", loads.load("a") { "a" })
        assertEquals("b", loads.load("b") { "b" })
    }
}
