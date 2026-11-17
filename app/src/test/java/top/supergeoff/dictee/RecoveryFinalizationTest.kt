package top.supergeoff.dictee

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryFinalizationTest {
    @Test fun cancellationAfterFirstSuccessReconcilesOutsideTransferLock() = runBlocking {
        val lock = Mutex()
        val waiting = CompletableDeferred<Unit>()
        val successes = mutableListOf<String>()
        val scheduled = mutableListOf<String>()
        var connected = false
        val job = launch {
            withRecoveryFinalization({ connected }, { lock.withLock { scheduled.addAll(successes) } }) {
                lock.withLock {
                    connected = true
                    successes += "a"
                    waiting.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
            }
        }
        waiting.await()
        job.cancelAndJoin()
        assertEquals(listOf("a"), scheduled)
        lock.withLock { assertEquals(listOf("a"), successes) }
    }
}
