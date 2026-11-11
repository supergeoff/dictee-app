package top.supergeoff.dictee

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RefreshCoordinatorTest {
    @Test
    fun `une reprise attend la fin du rafraichissement meme quand celui-ci echoue`() = runBlocking {
        val changes = mutableListOf<Boolean>()
        val coordinator = RefreshCoordinator { changes += it }
        val oldStarted = CompletableDeferred<Unit>()
        val failOld = CompletableDeferred<Unit>()
        val oldFailed = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        var reads = 0
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                coordinator.run(waitForCurrent = false) {
                    oldStarted.complete(Unit)
                    failOld.await()
                    try { throw IllegalStateException("chargement échoué") }
                    finally { oldFailed.complete(Unit); releaseOld.await() }
                }
            }
        }
        oldStarted.await()
        failOld.complete(Unit)
        oldFailed.await()
        val retry = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.run(waitForCurrent = true) { reads++ }
        }
        assertFalse(retry.isCompleted)
        assertEquals(0, reads)
        releaseOld.complete(Unit)
        assertTrue(withTimeout(5000) { old.await() }.isFailure)
        withTimeout(5000) { retry.await() }
        assertEquals(1, reads)
        assertEquals(listOf(true, false, true, false), changes)
    }

    @Test
    fun `une reprise lance aussi une lecture lorsque le precedent rafraichissement a deja fini`() = runBlocking {
        val changes = mutableListOf<Boolean>()
        val coordinator = RefreshCoordinator { changes += it }
        val failed = runCatching {
            coordinator.run(waitForCurrent = false) { throw IllegalStateException("refus") }
        }
        assertTrue(failed.isFailure)
        var reads = 0
        coordinator.run(waitForCurrent = true) { reads++ }
        assertEquals(1, reads)
        assertEquals(listOf(true, false, true, false), changes)
    }

    @Test
    fun `une requete ordinaire concurrente est ignoree et une attente annulee ne bloque pas`() = runBlocking {
        val coordinator = RefreshCoordinator {}
        assertTrue(coordinator.tryStart()) // L'association utilise aussi ce verrou.
        var reads = 0
        coordinator.run(waitForCurrent = false) { reads++ }
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.run(waitForCurrent = true) { reads++ }
        }
        assertFalse(waiting.isCompleted)
        waiting.cancel()
        waiting.join()
        coordinator.finish()
        coordinator.run(waitForCurrent = false) { reads++ }
        assertEquals(1, reads)
    }
}
