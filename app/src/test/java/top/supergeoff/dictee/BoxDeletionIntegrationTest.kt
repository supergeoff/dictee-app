package top.supergeoff.dictee

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BoxDeletionIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val a = "20261117120000"
    private val b = "20261117120100"
    private fun entry(name: String) = ListItem.Entry(name, 1, 1)
    private fun repository() = LocalStateRepository(LocalStateStore(File(tmp.root, "state.json")))
    private fun plan(vararg names: String) = BoxDeletionPlan("box", names.toList(), emptySet(), 0)

    @Test fun changedAddressAfterWaitingForTransferStopsBeforeSession() = runBlocking {
        val repo = repository(); val safety = RecoverySafety(repo); val guard = RecoveryDeletionGuard()
        repo.updateDurably { it.copy(deviceAddress = "box") }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val association = async { safety.withTransferLock { entered.complete(Unit); release.await(); repo.updateDurably { it.copy(deviceAddress = "other") } } }
        entered.await()
        var sessions = 0
        val deletion = async { runGuardedBoxDeletion(plan(a), { block -> safety.withTransferLock(block) }, guard,
            { repo.awaitInitialized().deviceAddress to emptySet() }, { _, _ -> sessions++; error("unexpected session") }) }
        release.complete(Unit); association.await()
        assertNotNull(deletion.await().error); assertEquals(0, sessions); assertNotNull(guard.capture(setOf(a)))
    }

    @Test fun sendAdmissionBeforeReservationBlocksBatchAndAfterSnapshotIsRefused() = runBlocking {
        val repo = repository(); val safety = RecoverySafety(repo); val guard = RecoveryDeletionGuard()
        val send = guard.admitSend(setOf(a))!!
        var emissions = 0
        val blocked = runGuardedBoxDeletion(plan(a, b), { block -> safety.withTransferLock(block) }, guard,
            { "box" to emptySet() }, { _, _ -> emissions++; error("unexpected emission") })
        assertNotNull(blocked.error); assertEquals(0, emissions)
        send.close()
        val snapshot = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val deletion = async { runGuardedBoxDeletion(plan(a), { block -> safety.withTransferLock(block) }, guard,
            { "box" to emptySet() }, { _, _ ->
                snapshot.complete(Unit); release.await(); emissions++
                BoxDeletionResult(setOf(a), emptyMap(), emptySet(), emptySet(), null)
            }) }
        snapshot.await()
        assertNull(guard.admitSend(setOf(a)))
        assertEquals(0, emissions)
        release.complete(Unit); deletion.await()
        guard.admitSend(setOf(a))!!.close()
        assertEquals(1, emissions)
    }

    @Test fun cancelledAndRepeatedConfirmationCannotEmitAgain() = runBlocking {
        val repo = repository(); val safety = RecoverySafety(repo); val guard = RecoveryDeletionGuard()
        val requests = BoxDeletionRequests()
        var emissions = 0
        suspend fun confirm() {
            val selected = requests.confirm() ?: return
            try {
                runGuardedBoxDeletion(selected, { block -> safety.withTransferLock(block) }, guard,
                    { "box" to emptySet() }, { _, names ->
                        emissions++
                        BoxDeletionResult(names.toSet(), emptyMap(), emptySet(), emptySet(), null)
                    })
            } finally { requests.finish() }
        }
        requests.open(plan(a)); requests.cancel(); confirm()
        assertEquals(0, emissions)
        requests.open(plan(a)); confirm(); confirm()
        assertEquals(1, emissions)
        assertNotNull(guard.capture(setOf(a)))
    }

    @Test fun oneProtectedNameBlocksEntireBatch() = runBlocking {
        val repo = repository(); val safety = RecoverySafety(repo); val guard = RecoveryDeletionGuard()
        repo.updateDurably { it.copy(deviceAddress = "box") }
        var sessions = 0
        for (protected in listOf(setOf(a), setOf(b))) {
            val result = runGuardedBoxDeletion(plan(a, b), { block -> safety.withTransferLock(block) }, guard,
                { repo.awaitInitialized().deviceAddress to deletionProtectedNames(repo.awaitInitialized(), emptySet(), protected) },
                { _, _ -> sessions++; error("unexpected session") })
            assertEquals(setOf(a, b), result.untreated); assertNotNull(result.error)
        }
        assertEquals(0, sessions); assertNotNull(guard.capture(setOf(a, b)))
    }

    @Test fun pendingProcessingFromRepositoryBlocksDeletion() = runBlocking {
        val repo = repository(); val safety = RecoverySafety(repo); val guard = RecoveryDeletionGuard()
        repo.updateDurably { it.copy(deviceAddress = "box", transcriptionTasks = listOf(sampleTask().copy(recordingName = b))) }
        var emitted = 0
        val result = runGuardedBoxDeletion(plan(a, b), { block -> safety.withTransferLock(block) }, guard,
            { val local = repo.awaitInitialized(); local.deviceAddress to deletionProtectedNames(local, emptySet(), emptySet()) },
            { _, _ -> emitted++; error("unexpected session") })
        assertNotNull(result.error); assertEquals(0, emitted); assertNotNull(guard.capture(setOf(a, b)))
    }

    @Test fun realSessionListsAfterDeleteAndPersistsOnlyBoxState() = runBlocking {
        val repo = repository(); val safety = RecoverySafety(repo); val guard = RecoveryDeletionGuard()
        repo.updateDurably { it.copy(deviceAddress = "box", boxList = listOf(entry(a), entry(b)), phoneTreeUri = "folder") }
        var deletes = 0
        val link = FakeLink { cmd, _ -> when (cmd) {
            Cmd.DELETE -> { deletes++; if (deletes == 1) reply(Cmd.DELETE, byteArrayOf(0)) else disconnect() }
            Cmd.BEFORE_LIST -> reply(Cmd.BEFORE_LIST, byteArrayOf(0))
            Cmd.LIST -> { reply(Cmd.LIST, byteArrayOf(0) + b.toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, 0, 1, 1)); reply(Cmd.LIST, byteArrayOf(0)) }
        } }
        val session = V05Session(link, {})
        val result = runGuardedBoxDeletion(plan(a, b), { block -> safety.withTransferLock(block) }, guard,
            { val local = repo.awaitInitialized(); local.deviceAddress to deletionProtectedNames(local, emptySet(), emptySet()) },
            { _, names -> BoxDeletionJob(session::deleteRecord, session::listRecords,
                { list -> repo.updateDurably { it.copy(boxList = list) } }).run(names) })
        assertEquals(setOf(a), result.confirmed); assertEquals(setOf(b), result.unverified)
        assertEquals(listOf(entry(b)), repo.awaitInitialized().boxList)
        assertEquals("folder", repo.awaitInitialized().phoneTreeUri)
        assertTrue(repo.awaitInitialized().transcriptionTasks.isEmpty())
        assertEquals(listOf(Cmd.DELETE, Cmd.BEFORE_LIST, Cmd.LIST, Cmd.DELETE), link.written.map { it[3].toInt() and 255 })
        val files = listOf(PhoneFile("Note-$a.mp3", 1))
        assertTrue(Recordings.merge(repo.awaitInitialized().boxList, files, null, emptyMap(), Busy()) { _, _ -> }.any { it.name == a && !it.onBox })
        assertFalse(Recordings.merge(repo.awaitInitialized().boxList, emptyList(), null, emptyMap(), Busy()) { _, _ -> }.any { it.name == a })
        val driveFile = DriveFile("id", "Note-$a.mp3", 1)
        val drive = DriveListing(mapOf(driveFile.name to driveFile.size), listOf(driveFile))
        assertTrue(Recordings.merge(repo.awaitInitialized().boxList, emptyList(), drive, emptyMap(), Busy()) { _, _ -> }.any { it.name == a && !it.onBox })
        repo.updateDurably { it.copy(boxList = listOf(entry(b))) }
        assertFalse(repo.awaitInitialized().boxList.any { it.name == a })
    }

    @Test fun deletionInvalidatesCapturedRecoveryAndSerializesAnotherDeletion() = runBlocking {
        val repo = repository(); val safety = RecoverySafety(repo); val guard = RecoveryDeletionGuard()
        val captured = guard.capture(setOf(a))!!
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val first = async { runGuardedBoxDeletion(plan(a), { block -> safety.withTransferLock(block) }, guard,
            { "box" to emptySet() }, { _, _ -> entered.complete(Unit); release.await(); BoxDeletionResult(setOf(a), emptyMap(), emptySet(), emptySet(), null) }) }
        entered.await(); assertNull(guard.capture(setOf(a))); assertNotNull(guard.capture(setOf(b)))
        val second = async { runGuardedBoxDeletion(plan(b), { block -> safety.withTransferLock(block) }, guard,
            { "box" to emptySet() }, { _, _ -> BoxDeletionResult(setOf(b), emptyMap(), emptySet(), emptySet(), null) }) }
        release.complete(Unit)
        withTimeout(5000) { first.await(); second.await() }
        assertFalse(guard.isCurrent(captured, a)); assertNotNull(guard.capture(setOf(a, b)))
    }

    @Test fun partialListingIsDurableAndCancellationReleasesReservation() = runBlocking {
        val repo = repository(); val safety = RecoverySafety(repo); val guard = RecoveryDeletionGuard()
        repo.updateDurably { it.copy(deviceAddress = "box", boxList = listOf(entry(a), entry(b))) }
        val waiting = CompletableDeferred<Unit>()
        var latest: BoxDeletionResult? = null
        val sent = mutableListOf<String>()
        val job = launch { runGuardedBoxDeletion(plan(a, b), { block -> safety.withTransferLock(block) }, guard,
            { repo.awaitInitialized().deviceAddress to emptySet() }, { _, names ->
                BoxDeletionJob({ name -> sent += name; if (name == b) { waiting.complete(Unit); CompletableDeferred<Unit>().await() }; 0 },
                    { listOf(entry(b)) }, { list -> repo.updateDurably { it.copy(boxList = list) } }, { latest = it }).run(names)
            }) }
        waiting.await(); job.cancelAndJoin()
        assertEquals(listOf(a, b), sent); assertEquals(setOf(a), latest!!.confirmed); assertEquals(setOf(b), latest!!.unverified)
        assertEquals(listOf(entry(b)), repo.awaitInitialized().boxList); assertNotNull(guard.capture(setOf(a, b)))
        assertTrue(repo.awaitInitialized().automaticProcessingRequests.isEmpty())
    }
}
