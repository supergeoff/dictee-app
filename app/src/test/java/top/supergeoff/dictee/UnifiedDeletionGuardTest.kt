package top.supergeoff.dictee

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Garde de la suppression unifiée : admissions de traitement et d'envoi, noms protégés, calcul des noms occupés. */
class UnifiedDeletionGuardTest {
    @get:Rule val tmp = TemporaryFolder()
    private val a = "20261117120000"
    private val b = "20261117120100"
    private fun repository() = LocalStateRepository(LocalStateStore(File(tmp.root, "state.json")))

    @Test fun processingAdmissionBeforeDeletionBlocksAndCancellationReleases() = runBlocking {
        for (names in listOf(setOf(a), null)) {
            val repo = repository(); val safety = RecoverySafety(repo); val guard = RecoveryDeletionGuard()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val holder = launch { safety.withTransferLock { entered.complete(Unit); release.await() } }
            entered.await()
            var executions = 0
            var busy = emptySet<String>()
            val deletion = async { safety.withTransferLock { guard.withDeletionSnapshot(setOf(a, b)) { admitted ->
                busy = unifiedDeletionBusyNames(setOf(a, b), emptySet(), admitted)
                if (busy.isEmpty()) executions++
            } } }
            yield()
            val job = launchProcessingRequest(this, guard, names) {
                safety.withTransferLock { error("processing must remain queued") }
            }!!
            yield()
            release.complete(Unit); holder.join()
            deletion.await()
            assertEquals(names ?: setOf(a, b), busy)
            job.cancelAndJoin()
            assertEquals(0, executions)
            guard.withDeletionSnapshot(setOf(a)) { assertTrue(it.isEmpty()) }
        }
    }

    @Test fun processingAfterReservationIsRefusedBeforeLaunch() = runBlocking {
        val guard = RecoveryDeletionGuard()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val deletion = async { guard.withDeletionSnapshot(setOf(a)) { entered.complete(Unit); release.await() } }
        entered.await()
        var starts = 0
        assertNull(launchProcessingRequest(this, guard, setOf(a)) { starts++ })
        assertNull(launchProcessingRequest(this, guard, null) { starts++ })
        release.complete(Unit); deletion.await()
        launchProcessingRequest(this, guard, setOf(a)) { starts++ }!!.join()
        assertEquals(1, starts)
    }

    @Test fun sendAdmissionBeforeReservationBlocksBatchAndAfterSnapshotIsRefused() = runBlocking {
        val guard = RecoveryDeletionGuard()
        val send = guard.admitSend(setOf(a))!!
        val admitted = guard.withDeletionSnapshot(setOf(a, b)) { it }
        assertEquals(setOf(a), admitted)
        assertEquals(setOf(a), unifiedDeletionBusyNames(setOf(a, b), emptySet(), admitted))
        send.close()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val deletion = async { guard.withDeletionSnapshot(setOf(a)) { entered.complete(Unit); release.await() } }
        entered.await()
        assertNull(guard.admitSend(setOf(a)))
        release.complete(Unit); deletion.await()
        guard.admitSend(setOf(a))!!.close()
    }

    @Test fun oneProtectedNameBlocksEntireBatch() = runBlocking {
        val repo = repository()
        repo.updateDurably { it.copy(deviceAddress = "box") }
        for (protected in listOf(setOf(a), setOf(b))) {
            val protectedNames = deletionProtectedNames(repo.awaitInitialized(), emptySet(), protected)
            assertEquals(protected, unifiedDeletionBusyNames(setOf(a, b), protectedNames, emptySet()))
        }
    }

    @Test fun pendingProcessingFromRepositoryBlocksDeletion() = runBlocking {
        val repo = repository()
        repo.updateDurably { it.copy(deviceAddress = "box", transcriptionTasks = listOf(sampleTask().copy(recordingName = b))) }
        val protectedNames = deletionProtectedNames(repo.awaitInitialized(), emptySet(), emptySet())
        assertEquals(setOf(b), protectedNames)
        assertEquals(setOf(b), unifiedDeletionBusyNames(setOf(a, b), protectedNames, emptySet()))
    }

    @Test fun busyNamesIgnoreProtectedNamesOutsideTheBatch() {
        assertEquals(emptySet<String>(), unifiedDeletionBusyNames(setOf(a), setOf(b), emptySet()))
        assertEquals(emptySet<String>(), unifiedDeletionBusyNames(setOf(a), emptySet(), setOf(b)))
        assertEquals(setOf(a, b), unifiedDeletionBusyNames(setOf(a, b), setOf(a), setOf(b)))
    }
}
