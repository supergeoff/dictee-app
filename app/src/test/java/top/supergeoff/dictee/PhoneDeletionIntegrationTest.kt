package top.supergeoff.dictee

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class PhoneDeletionIntegrationTest {
    @Test fun folderChangeAndActiveTargetsPreventDeletion() = runBlocking {
        var folder = "new"
        var protected = emptySet<String>()
        var calls = 0
        val runner = PhoneDeletionOrchestrator(
            withTransferLock = { it() }, withPhoneLock = { it() },
            snapshot = { DeletionSnapshot(folder, emptyList(), protected, null, LocalState(), emptyMap()) },
            delete = { calls++; PhoneDeletionResult(emptySet(), emptyMap()) },
            reread = { },
        )
        assertTrue(runner.execute("old", setOf("a")) is DeletionOutcome.FolderChanged)
        folder = "old"
        // The snapshot is read only after the locks are acquired.
        protected = setOf("a")
        assertTrue(runner.execute("old", setOf("a")) is DeletionOutcome.Blocked)
        assertEquals(0, calls)
    }
    @Test fun waitingForTransferRevalidatesAndBlocksWholeSelection() = runBlocking {
        val lock = Mutex()
        val acquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async { lock.withLock { acquired.complete(Unit); release.await() } }
        acquired.await()
        var protected = emptySet<String>()
        var deleted = false
        val runner = PhoneDeletionOrchestrator(
            { block -> lock.withLock { block() } }, { it() },
            { DeletionSnapshot("folder", emptyList(), protected, null, LocalState(), emptyMap()) },
            { deleted = true; PhoneDeletionResult(emptySet(), emptyMap()) }, { },
        )
        val pending = async { runner.execute("folder", setOf("a", "b")) }
        yield()
        protected = setOf("b")
        release.complete(Unit); holder.await()
        assertEquals(DeletionOutcome.Blocked(setOf("b")), pending.await())
        assertFalse(deleted)
    }

    @Test fun partialFailureRereadsActualFilesAndReportsRefusal() = runBlocking {
        val name = "20261117120000"
        val mp3 = "Note-$name.mp3"
        val vtt = "Note-$name.vtt"
        val memory = MemoryStore().apply { files[mp3] = byteArrayOf(1); files[vtt] = byteArrayOf(2) }
        val store = object : PhoneStore by memory {
            override fun delete(fileName: String) {
                if (fileName == vtt) throw PhoneStoreException("refus")
                memory.delete(fileName)
            }
        }
        val recording = Recording(name, RecordType.NOTE, 1, false,
            mapOf(FileKind.MP3 to PhoneFile(mp3, 1), FileKind.VTT to PhoneFile(vtt, 1)), DisplayState.Recovered)
        var reread = emptySet<String>()
        val runner = PhoneDeletionOrchestrator({ it() }, { it() },
            { DeletionSnapshot("folder", listOf(recording), emptySet(), null, LocalState(), emptyMap()) },
            { PhoneDeletion.execute(it, store) }, { reread = memory.files.keys.toSet() })
        val outcome = runner.execute("folder", setOf(name)) as DeletionOutcome.Completed
        assertEquals(setOf(mp3), outcome.result.deleted)
        assertEquals(mapOf(vtt to "refus"), outcome.result.failures)
        assertEquals(setOf(vtt), reread)
    }

    @Test fun producerWaitsForDeletionToReleaseTransferLock() = runBlocking {
        val lock = Mutex()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var started = false
        val runner = PhoneDeletionOrchestrator({ block -> lock.withLock { block() } }, { it() },
            { DeletionSnapshot("folder", emptyList(), emptySet(), null, LocalState(), emptyMap()) },
            { entered.complete(Unit); release.await(); PhoneDeletionResult(emptySet(), emptyMap()) }, { })
        val deletion = async { runner.execute("folder", setOf("a")) }
        entered.await()
        val producer = async { lock.withLock { started = true } }
        yield()
        assertFalse(started)
        release.complete(Unit); deletion.await(); producer.await()
        assertTrue(started)
    }

    @Test fun readFailureDoesNotHidePartialFailure() = runBlocking {
        val runner = PhoneDeletionOrchestrator({ it() }, { it() },
            { DeletionSnapshot("folder", emptyList(), emptySet(), null, LocalState(), emptyMap()) },
            { PhoneDeletionResult(setOf("a"), mapOf("b" to "refus")) },
            { throw PhoneStoreException("lecture impossible") })
        val result = runner.execute("folder", emptySet()) as DeletionOutcome.Completed
        assertEquals(mapOf("b" to "refus"), result.result.failures)
        assertEquals("lecture impossible", result.readError)
    }

}
