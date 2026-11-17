package top.supergeoff.dictee

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class RecoveryDeletionGuardTest {
    @Test fun recoveryStartedAfterDeletionSnapshotCannotWriteAfterLockRelease() = runBlocking {
        val guard = RecoveryDeletionGuard()
        val lock = Mutex()
        val snapshot = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val deletion = async {
            lock.withLock {
                guard.withDeletion(setOf("a")) {
                    snapshot.complete(Unit)
                    release.await()
                }
            }
        }
        snapshot.await()
        val token = guard.capture(setOf("a"))
        assertNull(token)
        assertNotNull(guard.capture(setOf("b")))
        var wrote = false
        val waiting = CompletableDeferred<Unit>()
        val recovery = async {
            waiting.complete(Unit)
            lock.withLock { if (token != null && guard.isCurrent(token, "a")) wrote = true }
        }
        waiting.await()
        release.complete(Unit)
        deletion.await()
        recovery.await()
        assertFalse(wrote)
    }

    @Test fun preparedRecoveryIsInvalidatedOnlyForOverlappingTargets() = runBlocking {
        val guard = RecoveryDeletionGuard()
        val token = guard.capture(setOf("a", "b"))!!
        val lock = Mutex()
        val deleting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val deletion = async {
            lock.withLock {
                guard.withDeletion(setOf("a")) { deleting.complete(Unit); release.await() }
            }
        }
        deleting.await()
        val waiting = CompletableDeferred<Unit>()
        var wrote = false
        val recovery = async {
            waiting.complete(Unit)
            lock.withLock { if (guard.isCurrent(token, "a")) wrote = true }
        }
        waiting.await()
        release.complete(Unit)
        deletion.await()
        recovery.await()
        assertFalse(wrote)
        assertFalse(guard.isCurrent(token, "a"))
        assertTrue(guard.isCurrent(token, "b"))
        assertNotNull(guard.capture(setOf("a")))
    }
    @Test fun sendAndDeletionAdmissionsAreAtomicInBothOrders() = runBlocking {
        val guard = RecoveryDeletionGuard()
        val send = guard.admitSend(setOf("a"))!!
        guard.withDeletionSnapshot(setOf("a", "b")) { busy ->
            assertEquals(setOf("a"), busy)
        }
        send.close()
        val reserved = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val deletion = async { guard.withDeletionSnapshot(setOf("a")) { busy ->
            assertTrue(busy.isEmpty())
            reserved.complete(Unit)
            release.await()
        } }
        reserved.await()
        assertNull(guard.admitSend(setOf("a")))
        val other = guard.admitSend(setOf("b"))!!
        other.close()
        release.complete(Unit)
        deletion.await()
        guard.admitSend(setOf("a"))!!.close()
    }
}
