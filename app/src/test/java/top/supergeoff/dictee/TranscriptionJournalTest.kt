package top.supergeoff.dictee

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/** Journalisation DIC-25 : les lignes STT et LLM racontent la vie d'une demande, sans jamais y mettre de clé. */
class TranscriptionJournalTest {
    @get:Rule val tmp = TemporaryFolder()
    private val file get() = File(tmp.root, "state.json")
    private val store get() = LocalStateStore(file)

    private val vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nBonjour\n".toByteArray()

    private class FakeStt(var onGet: suspend (TranscriptionTask) -> ServerTask = {
        ServerTask("server-1", ServerStatus.IN_PROGRESS, 0.4, null)
    }) : TranscriptionClient {
        override suspend fun submit(task: TranscriptionTask) = SubmitResult.Accepted(ServerTask("server-1", ServerStatus.QUEUED, null, null))
        override suspend fun get(task: TranscriptionTask) = onGet(task)
        override suspend fun cancel(task: TranscriptionTask): ServerTask = error("unexpected")
        override suspend fun test(settings: TranscriptionSettings) = Unit
    }

    private fun coordinator(
        repository: LocalStateRepository, client: TranscriptionClient, log: ListLog,
        prepare: suspend (Set<String>?) -> List<TranscriptionTask> = { emptyList() },
        saveVtt: suspend (TranscriptionTask, ByteArray, ((() -> Unit) -> Unit)) -> Unit = { _, _, _ -> },
    ) = TranscriptionCoordinator(repository, client, TranscriptionSettingsManager(repository, FakeCredentials()),
        RecoverySafety(repository), verifyAudio = { true }, log = log, prepare = prepare, saveVtt = saveVtt, schedule = {})

    private fun task(phase: TranscriptionPhase) = TranscriptionTask("local-1", "20261111120000", "Note-20261111120000.mp3",
        "Note-20261111120000.vtt", "content://folder", "hash", "https://example.invalid/v1", "ref", "fr", "model", phase)

    @Test fun `start ecrit une ligne par tache creee avec son rang dans la file`() = runBlocking {
        val log = ListLog()
        val coordinator = coordinator(LocalStateRepository(store), FakeStt(), log,
            prepare = { listOf(task(TranscriptionPhase.PENDING)) })
        coordinator.start(null)
        assertEquals(listOf("STT Note-20261111120000 : tâche créée, 1e dans la file"), log.lines)
    }

    @Test fun `les transitions de phase et le VTT enregistre sont journalises`() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(task(TranscriptionPhase.TRACKING).copy(serverId = "server-1"))))
        val log = ListLog()
        val client = FakeStt { ServerTask("server-1", ServerStatus.COMPLETED, 1.0, vtt) }
        val saved = mutableListOf<String>()
        val coordinator = coordinator(LocalStateRepository(store), client, log,
            saveVtt = { t, _, promote -> promote { saved += t.vttName } })
        coordinator.runBounded("local-1")
        assertEquals(listOf("Note-20261111120000.vtt"), saved)
        assertTrue(log.lines.contains("STT Note-20261111120000 : phase SAVING"))
        assertTrue(log.lines.contains("STT Note-20261111120000 : VTT enregistré"))
        assertTrue(log.lines.contains("STT Note-20261111120000 : phase COMPLETED"))
    }

    @Test fun `le parcours MD complet journalise les etapes de la synthese`() = runBlocking {
        val phone = MemoryStore().apply {
            files["Note-20261111120000.mp3"] = "audio".toByteArray()
            files["Note-20261111120000.vtt"] = vtt
        }
        val repository = LocalStateRepository(store)
        val log = ListLog()
        val stt = object : TranscriptionClient {
            override suspend fun submit(task: TranscriptionTask): SubmitResult = error("le VTT existe : aucun envoi de MP3")
            override suspend fun get(task: TranscriptionTask): ServerTask = error("unexpected")
            override suspend fun cancel(task: TranscriptionTask): ServerTask = error("unexpected")
            override suspend fun test(settings: TranscriptionSettings) = Unit
        }
        val md = object : SynthesisClient {
            override suspend fun complete(context: SynthesisContext, vtt: ByteArray) = "synthèse".toByteArray()
            override suspend fun test(settings: SynthesisSettings, secret: String) = Unit
        }
        val coordinator = TranscriptionCoordinator(repository, stt, TranscriptionSettingsManager(repository, FakeCredentials()),
            RecoverySafety(repository), verifyAudio = { true }, schedule = {}, log = log,
            synthesisClient = md, synthesisStorage = SynthesisStorage(File(tmp.root, "stage")),
            phoneStore = { phone }, synthesisCredentialAccessible = { true },
            saveVtt = { t, bytes, promote -> promote { phone.files[t.vttName] = bytes } },
            prepareRequested = { names, request, active -> TranscriptionPreparation.prepare(phone, "content://folder",
                TranscriptionSettings("https://example.invalid/v1", "fr", "model", "ref"), emptyMap(), names,
                SynthesisSettings("https://example.invalid/v1", "model", "ref"), request, active) { "id" } })
        val id = coordinator.start(setOf("20261111120000")).single()
        assertEquals(RunOutcome.Finished, coordinator.runBounded(id))
        assertTrue(phone.files.containsKey("Note-20261111120000.md"))
        assertEquals(listOf(
            "LLM Note-20261111120000 : tâche créée, 1e dans la file",
            "LLM Note-20261111120000 : appel envoyé, tentative 1/3",
            "LLM Note-20261111120000 : MD enregistré",
        ), log.lines)
    }

    @Test fun `une suspension de synthese est une ligne d erreur avec la raison`() = runBlocking {
        val hash = TranscriptionAudio.sha256(vtt)
        val context = SynthesisContext("https://example.invalid/v1", "ref", "model", "template",
            PromptMetadata("Note", "date", "durée"), "prompt", hash)
        val work = SynthesisWork(SynthesisPhase.READY, "Note-20261111120000.md", false, context, 0, null, null, null)
        store.save(LocalState(transcriptionTasks = listOf(
            task(TranscriptionPhase.SYNTHESIZING).copy(mp3Sha256 = "", serverAddress = "", encryptedKeyRef = "",
                language = "", model = "", synthesis = work))))
        val repository = LocalStateRepository(store)
        val log = ListLog()
        val coordinator = TranscriptionCoordinator(repository, FakeStt(),
            TranscriptionSettingsManager(repository, FakeCredentials()), RecoverySafety(repository), verifyAudio = { true }, log = log,
            phoneStore = { MemoryStore().apply { files["Note-20261111120000.vtt"] = vtt } },
            synthesisCredentialAccessible = { false })
        assertEquals(RunOutcome.Suspended, coordinator.runBounded("local-1"))
        assertTrue(log.lines.any {
            it == "LLM erreur : Note-20261111120000 : suspendue, Clé de synthèse inaccessible. Vérifie les réglages."
        })
    }

    @Test fun `le client de transcription journalise envoi et progression sans la cle`() = runBlocking {
        val log = ListLog()
        val responses = ArrayDeque<Pair<Int, String>>()
        fun reply(code: Int, body: String) = responses.add(code to body)
        val client = UrlTranscriptionClient(FakeCredentials(), { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }, { url ->
            val (code, body) = responses.removeFirst()
            FakeConnection(url, code, body.toByteArray())
        }, 3000, log)
        val task = TranscriptionTask("local", "20261111120000", "Note-20261111120000.mp3", "Note-20261111120000.vtt",
            "folder", "hash", "https://example.invalid/v1", "ref", "fr", "model", TranscriptionPhase.PENDING)
        reply(200, "{\"id\":\"job-1\",\"status\":\"queued\"}")
        client.submit(task)
        reply(200, "{\"id\":\"job-1\",\"status\":\"in_progress\",\"progress\":0.62}")
        client.get(task.copy(serverId = "job-1"))
        reply(200, "{\"id\":\"job-1\",\"status\":\"in_progress\",\"progress\":0.62}")
        client.get(task.copy(serverId = "job-1"))
        reply(200, "{\"id\":\"job-1\",\"status\":\"in_progress\",\"progress\":0.63}")
        client.get(task.copy(serverId = "job-1"))
        assertEquals("STT Note-20261111120000 : MP3 envoyé, réponse 200 en 0 s", log.lines[0])
        assertEquals(listOf("STT Note-20261111120000 : serveur 62 %", "STT Note-20261111120000 : serveur 63 %"),
            log.lines.drop(1))
        assertFalse(log.lines.joinToString("\n").contains("synthetic-secret"))
    }

    @Test fun `le client de synthese journalise sa reponse sans la cle ni le contenu`() = runBlocking {
        val log = ListLog()
        val body = org.json.JSONObject().put("choices", org.json.JSONArray().put(
            org.json.JSONObject().put("message", org.json.JSONObject().put("role", "assistant").put("content", "résumé"))))
        val client = UrlSynthesisClient(FakeCredentials(), { url -> FakeConnection(url, 200, body.toString().toByteArray()) },
            3000, log)
        val context = SynthesisContext("https://example.invalid/v1", "ref", "model", "template",
            PromptMetadata("Note", "12/11/2026 12:00", "durée"), "prompt", "a".repeat(64))
        client.complete(context, vtt)
        assertEquals(1, log.lines.size)
        assertTrue(log.lines.single().startsWith("LLM Note 12/11/2026 12:00 : réponse 200 en "))
        assertFalse(log.lines.single().contains("synthetic-secret"))
        assertFalse(log.lines.single().contains("résumé"))
    }

    @Test fun `les demandes recues et ecartees sont journalisees par enregistrement`() {
        fun recording(name: String, vararg kinds: FileKind) = Recording(name, RecordType.NOTE, 100, false,
            kinds.associateWith { PhoneFile(RecordName.fileName(RecordType.NOTE, name, it), 100) }, DisplayState.Recovered)
        val withVtt = recording("20261101000000", FileKind.MP3, FileKind.VTT)
        val withoutVtt = recording("20261102000000", FileKind.MP3)
        val active = recording("20261103000000", FileKind.MP3)
        val missingMp3 = recording("20261104000000", FileKind.VTT)
        val events = requestJournalEvents(listOf(withVtt, withoutVtt, active, missingMp3), null, emptyMap(),
            setOf(active.name), RequestedWork.MISSING)
        assertEquals(listOf(
            JournalEvent(Source.LLM, "Note-20261101000000", "demande reçue (fichiers manquants)"),
            JournalEvent(Source.STT, "Note-20261102000000", "demande reçue (fichiers manquants)"),
            JournalEvent(Source.STT, "Note-20261103000000", "demande écartée, tâche déjà active"),
            JournalEvent(Source.LLM, "Note-20261104000000", "demande écartée, MP3 absent sur le téléphone"),
        ), events)
        val redo = requestJournalEvents(listOf(withVtt), setOf(withVtt.name), emptyMap(), emptySet(), RequestedWork.REDO_MD)
        assertEquals(listOf(JournalEvent(Source.LLM, "Note-20261101000000", "demande reçue (MD à refaire)")), redo)
    }
}
