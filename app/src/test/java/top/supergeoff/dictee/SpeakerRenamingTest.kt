package top.supergeoff.dictee

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SpeakerRenamingTest {
    @get:Rule val tmp = TemporaryFolder()
    private val folderUri = "content://folder"
    private val recording = "20261111120000"
    private val vttName = "Note-$recording.vtt"
    private val mdName = "Note-$recording.md"
    private val vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n<v SPEAKER_00>Bonjour\n\n00:00:01.000 --> 00:00:02.000\n<v SPEAKER_01>Salut\n".toByteArray()
    private val md = "# Synthèse\n\nSPEAKER_00 salue SPEAKER_01.\n".toByteArray()
    private val phone = MemoryStore()
    private var folder: PhoneStore = phone
    private val log = ListLog()
    private val store get() = LocalStateStore(File(tmp.root, "state.json"))
    private val stt = object : TranscriptionClient {
        override suspend fun submit(task: TranscriptionTask): SubmitResult = error("unexpected STT")
        override suspend fun get(task: TranscriptionTask): ServerTask = error("unexpected STT")
        override suspend fun cancel(task: TranscriptionTask): ServerTask = error("unexpected STT")
        override suspend fun test(settings: TranscriptionSettings) = Unit
    }
    private val credentials = object : CredentialStore {
        override fun save(secret: String) = "ref"
        override fun read(ref: String) = "synthetic"
        override fun remove(ref: String) = Unit
    }
    private var promotedFolders = mutableListOf<String>()

    private fun sha(name: String) = TranscriptionAudio.sha256(phone, name)
    private fun provenance() = SynthesisProvenance(folderUri, mdName, "model", sha(vttName), sha(mdName))
    private fun task(phase: TranscriptionPhase, suspended: Boolean = false, recordingName: String = recording) = TranscriptionTask(
        "id", recordingName, "Note-$recordingName.mp3", vttName, folderUri, "hash", "https://example.invalid/v1", "ref", "fr", "model", phase, suspended = suspended)
    private fun coordinator(repo: LocalStateRepository) = TranscriptionCoordinator(repo, stt, TranscriptionSettingsManager(repo, credentials),
        RecoverySafety(repo), verifyAudio = { true }, phoneStore = { folder }, clock = { 5_000L }, log = log,
        onFilesPromoted = { promotedFolders += it })

    private fun seed(provenance: (() -> SynthesisProvenance)? = null, tasks: List<TranscriptionTask> = emptyList(),
                     book: List<SpeakerName> = emptyList(), md: ByteArray? = this.md): LocalStateRepository {
        phone.files[vttName] = vtt
        if (md != null) phone.files[mdName] = md
        store.save(LocalState(synthesisProvenance = listOfNotNull(provenance?.invoke()), transcriptionTasks = tasks, speakerNames = book))
        return LocalStateRepository(store)
    }

    private fun rename(coordinator: TranscriptionCoordinator, from: String = "SPEAKER_00", to: String = "Marie") = runBlocking {
        coordinator.renameSpeaker(recording, folderUri, vttName, mdName, from, to)
    }

    @Test fun fullRenameKeepsProvenanceCurrentAndUpdatesBook() {
        val repo = seed(provenance = ::provenance)
        val before = SynthesisProvenance(folderUri, mdName, "model", TranscriptionAudio.sha256(vtt), TranscriptionAudio.sha256(md))
        assertEquals(SpeakerRenameResult.Done, rename(coordinator(repo)))
        assertEquals("Marie", SpeakerNames.labels(phone.files.getValue(vttName)).first())
        assertEquals("# Synthèse\n\nMarie salue SPEAKER_01.\n", String(phone.files.getValue(mdName)))
        val state = store.load()
        val entry = state.synthesisProvenance.single()
        assertEquals(sha(vttName), entry.vttSha256)
        assertEquals(sha(mdName), entry.mdSha256)
        assertEquals(before.model, entry.model)
        assertNotEquals(before.mdSha256, entry.mdSha256)
        assertNotEquals(before.vttSha256, entry.vttSha256)
        assertEquals(ProvenanceStatus.CURRENT, provenanceStatus(entry, folderUri, sha(vttName), sha(mdName)))
        assertEquals(listOf(SpeakerName("Marie", 5_000L)), state.speakerNames)
        assertEquals(listOf(folderUri), promotedFolders)
        assertTrue(log.lines.any { it.startsWith("APP ") && it.contains("Locuteur renommé") && !it.contains("Marie") })
    }

    @Test fun mdWithoutLabelRenamesVttOnlyAndKeepsProvenanceCurrent() {
        val plain = "# Synthèse\n\nPersonne n'est cité.\n".toByteArray()
        seed(md = plain)
        store.save(store.load().copy(synthesisProvenance = listOf(provenance())))
        val mdBefore = sha(mdName)
        assertEquals(SpeakerRenameResult.VttOnly, rename(coordinator(LocalStateRepository(store))))
        assertEquals("Marie", SpeakerNames.labels(phone.files.getValue(vttName)).first())
        assertArrayEquals(plain, phone.files.getValue(mdName))
        val entry = store.load().synthesisProvenance.single()
        assertEquals(mdBefore, entry.mdSha256)
        assertEquals(sha(vttName), entry.vttSha256)
        assertEquals(ProvenanceStatus.CURRENT, provenanceStatus(entry, folderUri, sha(vttName), sha(mdName)))
        assertEquals("Marie", store.load().speakerNames.single().name)
    }

    @Test fun missingMdRenamesVttOnly() {
        val repo = seed(md = null)
        assertEquals(SpeakerRenameResult.VttOnly, rename(coordinator(repo)))
        assertEquals("Marie", SpeakerNames.labels(phone.files.getValue(vttName)).first())
        assertFalse(mdName in phone.files)
        assertTrue(store.load().synthesisProvenance.isEmpty())
    }

    @Test fun activeOrSuspendedTaskRefusesWithoutWriting() {
        for (task in listOf(task(TranscriptionPhase.SYNTHESIZING), task(TranscriptionPhase.PENDING, suspended = true))) {
            val repo = seed(tasks = listOf(task))
            val result = rename(coordinator(repo))
            assertEquals(SpeakerRenameResult.Refused("Un traitement est en cours sur cet enregistrement."), result)
            assertArrayEquals(vtt, phone.files.getValue(vttName))
            assertArrayEquals(md, phone.files.getValue(mdName))
            assertTrue(store.load().speakerNames.isEmpty())
        }
    }

    @Test fun terminalOrOtherRecordingTaskDoesNotBlock() {
        val repo = seed(tasks = listOf(task(TranscriptionPhase.COMPLETED), task(TranscriptionPhase.SYNTHESIZING, recordingName = "20270101000000").copy(localId = "other")))
        assertEquals(SpeakerRenameResult.Done, rename(coordinator(repo)))
    }

    @Test fun invalidNameRefusesWithoutWriting() {
        val repo = seed()
        assertEquals(SpeakerRenameResult.Refused("Nom invalide."), rename(coordinator(repo), to = "a<b"))
        assertArrayEquals(vtt, phone.files.getValue(vttName))
        assertArrayEquals(md, phone.files.getValue(mdName))
    }

    private fun assertNothingWritten(vttBefore: ByteArray = vtt, mdBefore: ByteArray? = md) {
        assertArrayEquals(vttBefore, phone.files.getValue(vttName))
        if (mdBefore != null) assertArrayEquals(mdBefore, phone.files.getValue(mdName))
        assertTrue(store.load().speakerNames.isEmpty())
        assertTrue(promotedFolders.isEmpty())
    }

    @Test fun unknownLabelRefusesWithoutWriting() {
        val repo = seed(provenance = ::provenance)
        assertEquals(SpeakerRenameResult.Refused("Locuteur introuvable."), rename(coordinator(repo), from = "SPEAKER_09"))
        assertNothingWritten()
        assertEquals(provenance(), store.load().synthesisProvenance.single())
    }

    @Test fun sameNameRefusesWithoutWriting() {
        val repo = seed()
        assertEquals(SpeakerRenameResult.Refused("Le locuteur porte déjà ce nom."), rename(coordinator(repo), to = " SPEAKER_00 "))
        assertNothingWritten()
    }

    @Test fun nonUtf8MdRefusesBeforeAnyWrite() {
        val invalid = byteArrayOf(0xC3.toByte(), 0x28)
        val repo = seed(md = invalid)
        val result = rename(coordinator(repo))
        assertEquals(SpeakerRenameResult.Refused("Le MD n'est pas de l'UTF-8 valide."), result)
        assertNothingWritten(mdBefore = invalid)
    }

    @Test fun mdFailureAfterVttStillRecordsNameInBook() {
        val repo = seed(provenance = ::provenance)
        val provenanceBefore = provenance()
        folder = object : PhoneStore by phone {
            override fun writeGuarded(fileName: String, source: File, replace: Boolean, promote: ((() -> Unit) -> Unit)) {
                if (fileName == mdName) throw PhoneStoreException("Écriture impossible.")
                phone.writeGuarded(fileName, source, replace, promote)
            }
        }
        val result = rename(coordinator(repo))
        assertEquals(SpeakerRenameResult.Refused("Le VTT est renommé, mais pas le MD : Écriture impossible."), result)
        assertEquals("Marie", SpeakerNames.labels(phone.files.getValue(vttName)).first())
        assertEquals(listOf(SpeakerName("Marie", 5_000L)), store.load().speakerNames)
        assertEquals(listOf(provenanceBefore), store.load().synthesisProvenance)
        assertEquals(listOf(folderUri), promotedFolders)
    }

    @Test fun stateWriteFailureAfterBothFilesReportsBothRenamedAndNotifies() {
        seed(provenance = ::provenance)
        val repo = LocalStateRepository(LocalStateStore(File(tmp.root, "state.json"), rename = { _, _ -> false }))
        val result = rename(coordinator(repo))
        assertEquals(SpeakerRenameResult.Refused("VTT et MD renommés, mais l'état local n'a pas pu être enregistré."), result)
        assertEquals("Marie", SpeakerNames.labels(phone.files.getValue(vttName)).first())
        assertEquals("# Synthèse\n\nMarie salue SPEAKER_01.\n", String(phone.files.getValue(mdName)))
        assertEquals(listOf(folderUri), promotedFolders)
    }

    @Test fun vttChangedBeforePromotionRefusesAndLeavesMdIntact() {
        val repo = seed(provenance = ::provenance)
        val changed = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n<v SPEAKER_00>Modifié\n".toByteArray()
        folder = object : PhoneStore by phone {
            override fun writeGuarded(fileName: String, source: File, replace: Boolean, promote: ((() -> Unit) -> Unit)) {
                phone.files[vttName] = changed
                phone.writeGuarded(fileName, source, replace, promote)
            }
        }
        val result = rename(coordinator(repo))
        assertTrue(result is SpeakerRenameResult.Refused)
        assertArrayEquals(changed, phone.files.getValue(vttName))
        assertArrayEquals(md, phone.files.getValue(mdName))
        assertTrue(store.load().speakerNames.isEmpty())
    }

    @Test fun mdChangedBeforePromotionRefusesAfterVttRenamed() {
        val repo = seed(provenance = ::provenance)
        val changed = "# Modifié par ailleurs : SPEAKER_00\n".toByteArray()
        folder = object : PhoneStore by phone {
            override fun writeGuarded(fileName: String, source: File, replace: Boolean, promote: ((() -> Unit) -> Unit)) {
                if (fileName == mdName) phone.files[mdName] = changed
                phone.writeGuarded(fileName, source, replace, promote)
            }
        }
        val result = rename(coordinator(repo))
        assertTrue(result is SpeakerRenameResult.Refused)
        assertEquals("Marie", SpeakerNames.labels(phone.files.getValue(vttName)).first())
        assertArrayEquals(changed, phone.files.getValue(mdName))
    }

    @Test fun unknownProvenanceStaysUnknown() {
        val repo = seed()
        assertEquals(SpeakerRenameResult.Done, rename(coordinator(repo)))
        assertTrue(store.load().synthesisProvenance.isEmpty())
        assertEquals("Marie", store.load().speakerNames.single().name)
    }

    @Test fun staleProvenanceIsLeftUntouched() {
        val stale = SynthesisProvenance(folderUri, mdName, "model", "ancien", TranscriptionAudio.sha256(md))
        val repo = seed(provenance = { stale })
        assertEquals(SpeakerRenameResult.Done, rename(coordinator(repo)))
        assertEquals(listOf(stale), store.load().synthesisProvenance)
    }

    @Test fun renamingToExistingLabelMergesSpeakers() {
        val repo = seed()
        assertEquals(SpeakerRenameResult.Done, rename(coordinator(repo), from = "SPEAKER_01", to = "SPEAKER_00"))
        assertEquals(listOf("SPEAKER_00"), SpeakerNames.labels(phone.files.getValue(vttName)))
    }

    @Test fun bookRefreshesExistingNameWithoutDuplicate() {
        val repo = seed(book = listOf(SpeakerName("marie", 1L), SpeakerName("Paul", 2L)))
        assertEquals(SpeakerRenameResult.Done, rename(coordinator(repo)))
        assertEquals(listOf(SpeakerName("Marie", 5_000L), SpeakerName("Paul", 2L)), store.load().speakerNames)
    }
}
