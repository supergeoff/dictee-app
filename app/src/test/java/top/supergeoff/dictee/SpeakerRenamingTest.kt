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
    private val vtt3 = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n<v SPEAKER_00>Bonjour\n\n00:00:01.000 --> 00:00:02.000\n<v SPEAKER_01>Salut\n\n00:00:02.000 --> 00:00:03.000\n<v SPEAKER_02>Oui\n".toByteArray()
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

    private val s0 = SpeakerLabel("SPEAKER_00", "SPEAKER_00")
    private val s1 = SpeakerLabel("SPEAKER_01", "SPEAKER_01")
    private val s2 = SpeakerLabel("SPEAKER_02", "SPEAKER_02")
    private val s9 = SpeakerLabel("SPEAKER_09", "SPEAKER_09")

    private fun currentNames() = SpeakerNames.labels(phone.files.getValue(vttName)).map { it.current }.distinct()

    private fun rename(coordinator: TranscriptionCoordinator, renames: Map<SpeakerLabel, String> = mapOf(s0 to "Marie")) = runBlocking {
        coordinator.renameSpeakers(recording, folderUri, vttName, renames)
    }

    private fun assertNothingWritten(vttBefore: ByteArray = vtt) {
        assertArrayEquals(vttBefore, phone.files.getValue(vttName))
        assertArrayEquals(md, phone.files.getValue(mdName))
        assertTrue(store.load().speakerNames.isEmpty())
        assertTrue(promotedFolders.isEmpty())
    }

    @Test fun renameWritesVttOnlyAndLeavesMdAndProvenanceUntouched() {
        val repo = seed(provenance = ::provenance)
        val provenanceBefore = provenance()
        assertEquals(SpeakerRenameResult.Done, rename(coordinator(repo)))
        assertEquals(listOf("Marie", "SPEAKER_01"), currentNames())
        assertArrayEquals(md, phone.files.getValue(mdName))
        assertEquals(listOf(provenanceBefore), store.load().synthesisProvenance)
        assertEquals(listOf(SpeakerName("Marie", 5_000L)), store.load().speakerNames)
        assertEquals(listOf(folderUri), promotedFolders)
        assertTrue(log.lines.any { it.startsWith("APP ") && it.contains("Locuteurs renommés") && !it.contains("Marie") })
    }

    @Test fun renameWorksWithoutMd() {
        val repo = seed(md = null)
        assertEquals(SpeakerRenameResult.Done, rename(coordinator(repo)))
        assertEquals("Marie", currentNames().first())
        assertFalse(mdName in phone.files)
    }

    @Test fun severalRenamesAreOneVttWriteAndAllNamesEnterBook() {
        val repo = seed().also { phone.files[vttName] = vtt3 }
        val writes = mutableListOf<String>()
        folder = object : PhoneStore by phone {
            override fun writeGuarded(fileName: String, source: File, replace: Boolean, promote: ((() -> Unit) -> Unit)) {
                writes += fileName
                phone.writeGuarded(fileName, source, replace, promote)
            }
        }
        val result = rename(coordinator(repo), mapOf(s0 to " Marie ", s1 to "Paul D."))
        assertEquals(SpeakerRenameResult.Done, result)
        assertEquals(listOf(vttName), writes)
        assertEquals(listOf("Marie", "Paul D.", "SPEAKER_02"), currentNames())
        assertEquals(setOf("Marie", "Paul D."), store.load().speakerNames.map { it.name }.toSet())
        assertTrue(store.load().speakerNames.all { it.lastUsedAt == 5_000L })
        assertEquals(listOf(folderUri), promotedFolders)
    }

    @Test fun labelsAbsentFromTheMapStayUnchanged() {
        val repo = seed().also { phone.files[vttName] = vtt3 }
        assertEquals(SpeakerRenameResult.Done, rename(coordinator(repo), mapOf(s1 to "Paul")))
        assertEquals(listOf("SPEAKER_00", "Paul", "SPEAKER_02"), currentNames())
    }

    @Test fun severalLabelsToTheSameNameMerge() {
        val repo = seed()
        val result = rename(coordinator(repo), mapOf(s0 to "Marie", s1 to "Marie"))
        assertEquals(SpeakerRenameResult.Done, result)
        assertEquals(listOf("Marie"), currentNames())
        assertEquals(listOf(SpeakerName("Marie", 5_000L)), store.load().speakerNames)
    }

    @Test fun renamingToAnExistingLabelMergesSpeakers() {
        val repo = seed()
        assertEquals(SpeakerRenameResult.Done, rename(coordinator(repo), mapOf(s1 to "SPEAKER_00")))
        assertEquals(listOf("SPEAKER_00"), currentNames())
    }

    @Test fun swappingTwoLabelsIsApplied() {
        val repo = seed()
        assertEquals(SpeakerRenameResult.Done, rename(coordinator(repo), mapOf(s0 to "SPEAKER_01", s1 to "SPEAKER_00")))
        assertEquals(listOf("SPEAKER_01", "SPEAKER_00"), currentNames())
    }

    @Test fun emptyOrUnchangedMapRefusesWithoutWriting() {
        val repo = seed()
        val coordinator = coordinator(repo)
        for (renames in listOf(emptyMap(), mapOf(s0 to "SPEAKER_00"), mapOf(s0 to " SPEAKER_00 "))) {
            assertEquals(SpeakerRenameResult.Refused("Aucun nom modifié."), rename(coordinator, renames))
            assertNothingWritten()
        }
    }

    @Test fun activeOrSuspendedTaskRefusesWithoutWriting() {
        for (task in listOf(task(TranscriptionPhase.SYNTHESIZING), task(TranscriptionPhase.PENDING, suspended = true))) {
            val repo = seed(tasks = listOf(task))
            val result = rename(coordinator(repo))
            assertEquals(SpeakerRenameResult.Refused("Un traitement est en cours sur cet enregistrement."), result)
            assertNothingWritten()
        }
    }

    @Test fun terminalOrOtherRecordingTaskDoesNotBlock() {
        val repo = seed(tasks = listOf(task(TranscriptionPhase.COMPLETED), task(TranscriptionPhase.SYNTHESIZING, recordingName = "20270101000000").copy(localId = "other")))
        assertEquals(SpeakerRenameResult.Done, rename(coordinator(repo)))
    }

    @Test fun invalidNameRefusesWholeRenameWithoutWriting() {
        val repo = seed()
        assertEquals(SpeakerRenameResult.Refused("Nom invalide."),
            rename(coordinator(repo), mapOf(s0 to "Marie", s1 to "a<b")))
        assertNothingWritten()
    }

    @Test fun unknownLabelRefusesWholeRenameWithoutWriting() {
        val repo = seed(provenance = ::provenance)
        assertEquals(SpeakerRenameResult.Refused("Locuteur introuvable."),
            rename(coordinator(repo), mapOf(s0 to "Marie", s9 to "Paul")))
        assertNothingWritten()
        assertEquals(provenance(), store.load().synthesisProvenance.single())
    }

    @Test fun writeFailureRefusesAndLeavesBookUntouched() {
        val repo = seed()
        folder = object : PhoneStore by phone {
            override fun writeGuarded(fileName: String, source: File, replace: Boolean, promote: ((() -> Unit) -> Unit)) {
                throw PhoneStoreException("Écriture impossible.")
            }
        }
        assertEquals(SpeakerRenameResult.Refused("Écriture impossible."), rename(coordinator(repo)))
        assertNothingWritten()
    }

    @Test fun vttChangedBeforePromotionRefusesAndKeepsTheOtherWriter() {
        val repo = seed(provenance = ::provenance)
        val changed = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n<v SPEAKER_00>Modifié\n".toByteArray()
        folder = object : PhoneStore by phone {
            override fun writeGuarded(fileName: String, source: File, replace: Boolean, promote: ((() -> Unit) -> Unit)) {
                phone.files[vttName] = changed
                phone.writeGuarded(fileName, source, replace, promote)
            }
        }
        assertEquals(SpeakerRenameResult.Refused("Le VTT a changé."), rename(coordinator(repo)))
        assertArrayEquals(changed, phone.files.getValue(vttName))
        assertArrayEquals(md, phone.files.getValue(mdName))
        assertTrue(store.load().speakerNames.isEmpty())
        assertTrue(promotedFolders.isEmpty())
    }

    @Test fun unreadableVttRefusesWithoutWriting() {
        val invalid = byteArrayOf(0xC3.toByte(), 0x28)
        val repo = seed()
        phone.files[vttName] = invalid
        val result = rename(coordinator(repo))
        assertTrue(result is SpeakerRenameResult.Refused)
        assertNothingWritten(vttBefore = invalid)
    }

    @Test fun stateWriteFailureAfterVttReportsRenamedAndNotifies() {
        seed(provenance = ::provenance)
        val repo = LocalStateRepository(LocalStateStore(File(tmp.root, "state.json"), rename = { _, _ -> false }))
        val result = rename(coordinator(repo))
        assertEquals(SpeakerRenameResult.Refused("VTT renommé, mais l'état local n'a pas pu être enregistré."), result)
        assertEquals("Marie", currentNames().first())
        assertEquals(listOf(folderUri), promotedFolders)
    }

    @Test fun bookRefreshesExistingNameWithoutDuplicate() {
        val repo = seed(book = listOf(SpeakerName("marie", 1L), SpeakerName("Paul", 2L)))
        assertEquals(SpeakerRenameResult.Done, rename(coordinator(repo)))
        assertEquals(listOf(SpeakerName("Marie", 5_000L), SpeakerName("Paul", 2L)), store.load().speakerNames)
    }

    private val marie = SpeakerLabel("Marie", "SPEAKER_00")

    @Test fun renameKeepsTheOriginAsAClassInTheVtt() {
        val repo = seed()
        assertEquals(SpeakerRenameResult.Done, rename(coordinator(repo)))
        assertEquals(listOf(marie, s1), SpeakerNames.labels(phone.files.getValue(vttName)))
        assertTrue(String(phone.files.getValue(vttName)).contains("<v.spk-SPEAKER_00 Marie>Bonjour"))
    }

    @Test fun returningToTheOriginRestoresTheVttAndLeavesTheBookUntouched() {
        val repo = seed(book = listOf(SpeakerName("Marie", 1L)))
        phone.files[vttName] = SpeakerNames.renameInVtt(vtt, mapOf(s0 to "Marie"))
        assertEquals(SpeakerRenameResult.Done, rename(coordinator(repo), mapOf(marie to "SPEAKER_00")))
        assertArrayEquals(vtt, phone.files.getValue(vttName))
        assertEquals(listOf(SpeakerName("Marie", 1L)), store.load().speakerNames)
        assertArrayEquals(md, phone.files.getValue(mdName))
        assertEquals(listOf(folderUri), promotedFolders)
    }

    @Test fun onlyNewNamesEnterTheBookWhenAReturnIsMixedWithARename() {
        val repo = seed().also { phone.files[vttName] = SpeakerNames.renameInVtt(vtt3, mapOf(s0 to "Marie")) }
        assertEquals(SpeakerRenameResult.Done, rename(coordinator(repo), mapOf(marie to "SPEAKER_00", s1 to "Paul")))
        assertEquals(listOf("SPEAKER_00", "Paul", "SPEAKER_02"), currentNames())
        assertEquals(listOf(SpeakerName("Paul", 5_000L)), store.load().speakerNames)
    }

    @Test fun aMergeThenAReturnRestoresBothOriginalLabels() {
        val repo = seed()
        val coordinator = coordinator(repo)
        assertEquals(SpeakerRenameResult.Done, rename(coordinator, mapOf(s0 to "Marie", s1 to "Marie")))
        assertEquals(listOf(marie, SpeakerLabel("Marie", "SPEAKER_01")), SpeakerNames.labels(phone.files.getValue(vttName)))
        val result = rename(coordinator, mapOf(marie to "SPEAKER_00", SpeakerLabel("Marie", "SPEAKER_01") to "SPEAKER_01"))
        assertEquals(SpeakerRenameResult.Done, result)
        assertArrayEquals(vtt, phone.files.getValue(vttName))
        assertEquals(listOf(SpeakerName("Marie", 5_000L)), store.load().speakerNames)
    }

    @Test fun anOriginLongerThanANameCanStillBeRestored() {
        val origin = "X".repeat(45)
        val repo = seed()
        phone.files[vttName] = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n<v.spk-$origin Marie>Bonjour\n".toByteArray()
        assertEquals(SpeakerRenameResult.Done, rename(coordinator(repo), mapOf(SpeakerLabel("Marie", origin) to origin)))
        assertEquals(listOf(origin), currentNames())
        assertTrue(store.load().speakerNames.isEmpty())
    }

    @Test fun aSpeakerWithTheWrongOriginIsNotFound() {
        val repo = seed()
        assertEquals(SpeakerRenameResult.Refused("Locuteur introuvable."), rename(coordinator(repo), mapOf(SpeakerLabel("SPEAKER_00", "A") to "Marie")))
        assertNothingWritten()
    }
}
