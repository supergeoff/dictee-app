package top.supergeoff.dictee

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

class LocalStateTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun file() = File(tmp.root, "state.json")

    @Test
    fun version3TasksRemainVttOnly() {
        val phases = listOf(TranscriptionPhase.PENDING, TranscriptionPhase.TRACKING, TranscriptionPhase.SAVING, TranscriptionPhase.COMPLETED)
        val tasks = phases.mapIndexed { index, phase -> sampleTask().copy(localId = "old-$index", phase = phase, serverId = "server-$index") }
        val settings = TranscriptionSettings("https://example.invalid/v1", "en", "synthetic-model", "old-ref")
        val taskJson = phases.mapIndexed { index, phase ->
            """{"localId":"old-$index","recordingName":"20261030160502","mp3Name":"Note-20261030160502.mp3",
                "vttName":"Note-20261030160502.vtt","folderUri":"content://test/tree","mp3Sha256":"${"a".repeat(64)}",
                "serverAddress":"https://example.invalid/v1","encryptedKeyRef":"encrypted-key-ref","language":"fr",
                "model":"test-model","phase":"${phase.name}","serverId":"server-$index"}"""
        }.joinToString(",")
        file().writeText("""{"version":3,"transcriptionSettings":{"baseUrl":"https://example.invalid/v1",
            "language":"en","model":"synthetic-model","credentialRef":"old-ref"},"transcriptionTasks":[$taskJson]}""")
        val loaded = LocalStateStore(file()).load()
        assertEquals(tasks, loaded.transcriptionTasks)
        assertTrue(loaded.transcriptionTasks.all { it.synthesis == null && it.replaceVtt })
        assertEquals(settings, loaded.transcriptionSettings)
        assertEquals(SynthesisSettings(), loaded.synthesisSettings)
        assertTrue(loaded.synthesisProvenance.isEmpty())
    }

    @Test
    fun version4RoundTripKeepsBudgetAndProvenance() {
        val secret = "synthetic-cleartext-key"
        val credentials = object : CredentialStore {
            var stored = ""
            override fun save(secret: String): String { stored = secret; return "synthetic-ref" }
            override fun read(ref: String): String = stored
            override fun remove(ref: String) { stored = "" }
        }
        val ref = credentials.save(secret)
        assertEquals(secret, credentials.read(ref))
        val context = SynthesisContext("https://example.invalid/v1", ref, "synthetic-model", "template {{type}}",
            PromptMetadata("Note", "date", "2 min"), "prompt résolu", "a".repeat(64))
        val work = SynthesisWork(SynthesisPhase.CALLING, "Note.md", false, context, 2, 120000L, "b".repeat(64), null)
        val provenance = SynthesisProvenance("content://test/tree", "Note.md", "synthetic-model", "a".repeat(64), "b".repeat(64))
        val state = LocalState(transcriptionTasks = listOf(sampleTask().copy(synthesis = work, replaceVtt = false)),
            synthesisSettings = SynthesisSettings("https://example.invalid/v1", "synthetic-model", ref, "template"),
            synthesisProvenance = listOf(provenance))
        val store = LocalStateStore(file())
        store.save(state)
        assertEquals(4, org.json.JSONObject(file().readText()).getInt("version"))
        assertEquals(state, store.load())
        assertFalse(file().readText().contains(secret))
    }

    @Test
    fun synthesisOnlyTaskAcceptsEmptySttAddress() {
        val work = SynthesisWork(SynthesisPhase.SUSPENDED, "Note.md", false, null, 0, null, null, "Service non configuré")
        val task = sampleTask().copy(phase = TranscriptionPhase.SYNTHESIZING, mp3Sha256 = "", serverAddress = "",
            encryptedKeyRef = "", language = "", model = "", synthesis = work)
        val store = LocalStateStore(file())
        store.save(LocalState(transcriptionTasks = listOf(task)))
        assertEquals(task, store.load().transcriptionTasks.single())
        try {
            store.save(LocalState(transcriptionTasks = listOf(task.copy(phase = TranscriptionPhase.PENDING))))
            org.junit.Assert.fail("Une adresse STT vide ne doit pas être acceptée pour PENDING")
        } catch (_: InvalidTranscriptionAddressException) { }
    }

    @Test
    fun invalidFrozenSynthesisAddressIsScrubbedAndSuspended() {
        val store = LocalStateStore(file())
        val context = SynthesisContext("https://example.invalid/v1", "ref", "model", "template",
            PromptMetadata("Note", "date", "durée"), "prompt", "a".repeat(64))
        val work = SynthesisWork(SynthesisPhase.CALLING, "Note.md", false, context, 2, 120000L, null, null)
        val state = LocalState(transcriptionTasks = listOf(sampleTask().copy(synthesis = work)))
        try {
            store.save(state.copy(transcriptionTasks = listOf(sampleTask().copy(synthesis = work.copy(context = context.copy(baseUrl = "/v1"))))))
            org.junit.Assert.fail("Une adresse figée relative ne doit pas être enregistrée")
        } catch (_: InvalidTranscriptionAddressException) { }
        store.save(state)
        val json = org.json.JSONObject(file().readText())
        val secret = "synthetic-address-secret"
        json.getJSONArray("transcriptionTasks").getJSONObject(0).getJSONObject("synthesis").getJSONObject("context")
            .put("baseUrl", "https://user:$secret@example.invalid/v1")
        file().writeText(json.toString())
        val loaded = store.load()
        val migrated = loaded.transcriptionTasks.single().synthesis!!
        assertEquals(SynthesisPhase.SUSPENDED, migrated.phase)
        assertEquals("", migrated.context!!.baseUrl)
        assertTrue(migrated.suspensionReason != null)
        assertFalse(loaded.toString().contains(secret))
        store.save(loaded)
        assertFalse(file().readText().contains(secret))
        assertEquals(loaded, store.load())
    }

    @Test
    fun invalidSynthesisAddressesDoNotReviveTerminalWork() {
        val context = SynthesisContext("https://example.invalid/v1", "ref", "model", "template",
            PromptMetadata("Note", "date", "durée"), "prompt", "a".repeat(64))
        val phases = listOf(SynthesisPhase.COMPLETED, SynthesisPhase.CANCELLED)
        val tasks = phases.map { phase -> sampleTask().copy(localId = phase.name,
            synthesis = SynthesisWork(phase, "Note.md", false, context, 2, null, null, null)) }
        val store = LocalStateStore(file())
        store.save(LocalState(transcriptionTasks = tasks))
        val json = org.json.JSONObject(file().readText())
        val secret = "synthetic-secret-in-url"
        json.put("synthesisSettings", org.json.JSONObject().put("baseUrl", "https://user:$secret@example.invalid/v1"))
        val array = json.getJSONArray("transcriptionTasks")
        repeat(array.length()) { array.getJSONObject(it).getJSONObject("synthesis").getJSONObject("context").put("baseUrl", "https://user:$secret@example.invalid/v1") }
        file().writeText(json.toString())
        val loaded = store.load()
        assertEquals("/v1", loaded.synthesisSettings.baseUrl)
        assertEquals(phases, loaded.transcriptionTasks.map { it.synthesis!!.phase })
        assertTrue(loaded.transcriptionTasks.all { it.synthesis!!.context!!.baseUrl == "" })
        assertFalse(loaded.toString().contains(secret))
        store.save(loaded)
        assertEquals(loaded, store.load())
        assertFalse(file().readText().contains(secret))
    }

    @Test
    fun missingContextIsAllowedOnlyBeforeOrAfterUnconfiguredWork() {
        val store = LocalStateStore(file())
        val work = SynthesisWork(SynthesisPhase.WAITING_VTT, "Note.md", false, null, 0, null, null, null)
        for (phase in listOf(SynthesisPhase.WAITING_VTT, SynthesisPhase.SUSPENDED, SynthesisPhase.CANCELLED)) {
            val state = LocalState(transcriptionTasks = listOf(sampleTask().copy(synthesis = work.copy(phase = phase))))
            store.save(state)
            assertEquals(state, store.load())
        }
        for (phase in listOf(SynthesisPhase.READY, SynthesisPhase.CALLING, SynthesisPhase.SAVE_PENDING, SynthesisPhase.COMPLETED)) {
            try {
                store.save(LocalState(transcriptionTasks = listOf(sampleTask().copy(synthesis = work.copy(phase = phase)))))
                org.junit.Assert.fail("La phase $phase exige un contexte")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun callingWithoutContextCannotBeSavedOrLoaded() {
        val work = SynthesisWork(SynthesisPhase.CALLING, "Note.md", false, null, 1, null, null, null)
        val store = LocalStateStore(file())
        try {
            store.save(LocalState(transcriptionTasks = listOf(sampleTask().copy(synthesis = work))))
            org.junit.Assert.fail("Un appel ne peut pas être enregistré sans contexte")
        } catch (_: IllegalArgumentException) { }
        store.save(LocalState(transcriptionTasks = listOf(sampleTask().copy(synthesis = work.copy(phase = SynthesisPhase.SUSPENDED)))))
        val json = org.json.JSONObject(file().readText())
        json.getJSONArray("transcriptionTasks").getJSONObject(0).getJSONObject("synthesis").put("phase", "CALLING")
        file().writeText(json.toString())
        try { store.load(); org.junit.Assert.fail("Un appel sans contexte doit empêcher le chargement") }
        catch (_: org.json.JSONException) { }
    }

    @Test
    fun unknownSynthesisPhaseFailsLoadingWithoutChangingFile() {
        val store = LocalStateStore(file())
        store.save(LocalState(transcriptionTasks = listOf(sampleTask())))
        val json = org.json.JSONObject(file().readText()).put("version", 4)
        json.getJSONArray("transcriptionTasks").getJSONObject(0).put("synthesis", org.json.JSONObject()
            .put("phase", "FUTURE_PHASE").put("mdName", "Note.md").put("replaceMd", false)
            .put("context", org.json.JSONObject.NULL).put("attempts", 0))
        file().writeText(json.toString())
        val before = file().readText()
        try {
            store.load()
            org.junit.Assert.fail("Une phase MD inconnue doit empêcher le chargement")
        } catch (_: org.json.JSONException) { }
        assertEquals(before, file().readText())
    }

    @Test
    fun `l'etat se relit a l'identique`() {
        val s = LocalState("AA:BB", "MECHEN V05", listOf(ListItem.Entry("20261030160502", 9_880_000, 0)),
            "content://tree/primary%3ADownload%2FDict%C3%A9e", true, "user@example.com",
            listOf(KnownFolder("F1", "Dictée", FolderOrigin.CREATED)), "F1",
            mapOf("20261030160502" to mapOf(Step.SEND to "taille Drive 0 au lieu de 9880000")), ThemeChoice.DARK)
        LocalStateStore(file()).save(s)
        assertEquals(s, LocalStateStore(file()).load())
    }

    @Test
    fun `l'etat 0_1_5 garde le boitier et le dossier choisi`() {
        file().writeText("""{"deviceAddress":"AA:BB","deviceName":"MECHEN V05","driveFolderId":"P1",
            "driveFolderName":"Dictées","driveFolderPicked":true,
            "records":{"20261030084752":{"status":"SYNCED","size":24518,"lastAttempt":null,"lastError":null}}}""")
        val s = LocalStateStore(file()).load()
        assertEquals("AA:BB", s.deviceAddress); assertEquals(listOf(KnownFolder("P1", "Dictées", FolderOrigin.PICKED)), s.knownFolders)
        assertEquals("P1", s.chosenFolderId); assertTrue(s.driveEnabled); assertTrue(s.errors.isEmpty())
    }

    @Test
    fun `le dossier dictee cree par la 0_1_5 devient un dossier connu`() {
        file().writeText("""{"deviceAddress":null,"driveFolderId":"D1","driveFolderName":null,"driveFolderPicked":false,"records":{}}""")
        assertEquals(listOf(KnownFolder("D1", "dictee", FolderOrigin.CREATED)), LocalStateStore(file()).load().knownFolders)
    }

    @Test
    fun `un etat illisible reste intact ainsi que le fichier bad`() {
        val content = "json invalide"
        file().writeText(content)
        val bad = File(tmp.root, "state.json.bad").apply { writeText("ancienne sauvegarde") }
        try { LocalStateStore(file()).load(); org.junit.Assert.fail("le chargement doit échouer") } catch (_: org.json.JSONException) { }
        assertEquals(content, file().readText())
        assertEquals("ancienne sauvegarde", bad.readText())
    }

    @Test
    fun `une erreur de lecture ne deplace ni ne supprime les fichiers`() {
        file().mkdir()
        val bad = File(tmp.root, "state.json.bad").apply { writeText("ancienne sauvegarde") }
        try { LocalStateStore(file()).load(); org.junit.Assert.fail("le chargement doit échouer") } catch (_: java.io.IOException) { }
        assertTrue(file().isDirectory)
        assertEquals("ancienne sauvegarde", bad.readText())
    }

    @Test
    fun `une etape se retire pour tous les noms et laisse les autres etapes`() {
        val s = LocalState().withError("A", Step.SEND, "403").withError("B", Step.SEND, "500").withError("B", Step.RECOVER, "coupure")
        assertEquals(mapOf("B" to mapOf(Step.RECOVER to "coupure")), s.withoutErrors(Step.SEND).errors)
        assertEquals(LocalState(), LocalState().withoutErrors(Step.SEND))
    }

    @Test
    fun `une reussite retire l'erreur de l'etape`() {
        val s = LocalState().withError("N", Step.RECOVER, "coupure").withError("N", Step.SEND, "403")
        assertEquals(mapOf("N" to mapOf(Step.SEND to "403")), s.withoutError("N", Step.RECOVER).errors)
        assertTrue(s.withoutError("N", Step.RECOVER).withoutError("N", Step.SEND).errors.isEmpty())
    }

    @Test
    fun `un changement qui ne change rien ne demande pas d'ecriture`() {
        val flow = MutableStateFlow(LocalState())
        assertFalse(flow.updateChanged { it })
        assertFalse(flow.updateChanged { it.withoutError("N", Step.SEND) })   // rien à retirer
        assertTrue(flow.updateChanged { it.withError("N", Step.SEND, "403") })
        assertFalse(flow.updateChanged { it.withError("N", Step.SEND, "403") })   // même erreur, même état
        assertEquals(mapOf("N" to mapOf(Step.SEND to "403")), flow.value.errors)
        assertTrue(flow.updateChanged { it.withoutError("N", Step.SEND) })
        assertTrue(flow.value.errors.isEmpty())
    }

    @Test
    fun `deux enregistrements en parallele laissent un etat lisible`() {
        val store = LocalStateStore(file())
        val states = listOf(LocalState(deviceName = "A"), LocalState(deviceName = "B"))
        val failures = ConcurrentLinkedQueue<Throwable>()
        val threads = states.map { state ->
            Thread {
                try {
                    repeat(200) {
                        store.save(state)
                        if (store.load() !in states) failures.add(AssertionError("etat absent du fichier"))
                    }
                } catch (e: Throwable) {
                    failures.add(e)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(failures.isEmpty())
        assertTrue(store.load() in states)
    }
    @Test
    fun `migration v2 et aller retour des phases et erreurs sans cle en clair`() {
        file().writeText("""{"version":2,"deviceName":"boitier","theme":"DARK","errors":{"N":{"RECOVER":"coupure"}}}""")
        val store = LocalStateStore(file())
        val old = store.load()
        assertEquals("boitier", old.deviceName)
        assertEquals(ThemeChoice.DARK, old.theme)
        assertEquals(emptyList<TranscriptionTask>(), old.transcriptionTasks)
        val tasks = TranscriptionPhase.entries.mapIndexed { i, phase ->
            sampleTask().copy(localId = "task-$i", phase = phase, serverId = if (i % 2 == 0) "server-$i" else null,
                progress = if (i % 2 == 0) i / 10.0 else null, error = if (i == 7) "erreur serveur" else null,
                suspended = i == 3, suspensionReason = if (i == 3) "connexion absente" else null)
        }
        val state = old.copy(transcriptionTasks = tasks).withError("N", Step.TRANSCRIBE, "erreur")
        store.save(state)
        assertEquals(state, LocalStateStore(file()).load())
        assertTrue(file().readText().contains("encrypted-key-ref"))
        assertFalse(file().readText().contains("cleartext-api-key"))
        assertTrue(file().readText().contains("\"version\": 4"))
    }

    @Test
    fun `echec du renommage conserve le fichier et ne publie pas le changement`() {
        val store = LocalStateStore(file())
        store.save(LocalState(deviceName = "ancien"))
        val before = file().readText()
        val repository = LocalStateRepository(LocalStateStore(file(), rename = { _, _ -> false }))
        try {
            kotlinx.coroutines.runBlocking { repository.updateDurably { it.copy(deviceName = "nouveau") } }
            org.junit.Assert.fail("le renommage doit echouer")
        } catch (_: java.io.IOException) { }
        assertEquals(before, file().readText())
        assertEquals("ancien", repository.state.value!!.deviceName)
    }
    @Test
    fun `modifications concurrentes des reglages et de la tache sont conservees`() {
        val repository = LocalStateRepository(LocalStateStore(file()))
        val failures = ConcurrentLinkedQueue<Throwable>()
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = listOf(
            Thread { try { start.await(); kotlinx.coroutines.runBlocking {
                repository.updateDurably { it.copy(theme = ThemeChoice.DARK) }
            } } catch (e: Throwable) { failures.add(e) } },
            Thread { try { start.await(); kotlinx.coroutines.runBlocking {
                repository.updateDurably { it.copy(transcriptionTasks = listOf(sampleTask())) }
            } } catch (e: Throwable) { failures.add(e) } },
        )
        threads.forEach { it.start() }; start.countDown(); threads.forEach { it.join() }
        assertTrue(failures.toString(), failures.isEmpty())
        val restored = LocalStateStore(file()).load()
        assertEquals(ThemeChoice.DARK, restored.theme)
        assertEquals(listOf(sampleTask()), restored.transcriptionTasks)
        assertEquals(restored, repository.state.value)
    }

    @Test
    fun `une mise a jour durable identique confirme aussi une ecriture`() {
        val repository = LocalStateRepository(LocalStateStore(file(), rename = { _, _ -> false }))
        try {
            kotlinx.coroutines.runBlocking { repository.updateDurably { it } }
            org.junit.Assert.fail("l'ecriture doit echouer")
        } catch (_: java.io.IOException) { }
        assertFalse(file().exists())
    }
    @Test
    fun `une progression fractionnaire et une progression nulle se relisent sans perte`() {
        val store = LocalStateStore(file())
        val state = LocalState(transcriptionTasks = listOf(
            sampleTask().copy(progress = 0.35), sampleTask().copy(localId = "null"),
            sampleTask().copy(localId = "zero", progress = 0.0), sampleTask().copy(localId = "one", progress = 1.0),
        ))
        store.save(state)
        assertEquals(0.35, org.json.JSONObject(file().readText()).getJSONArray("transcriptionTasks").getJSONObject(0).getDouble("progress"), 0.0)
        assertEquals(state, LocalStateStore(file()).load())
    }

    @Test
    fun `une sauvegarde ordinaire ne bloque pas le thread appelant`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val returned = java.util.concurrent.CountDownLatch(1)
        val repo = LocalStateRepository(LocalStateStore(file(), rename = { from, to ->
            entered.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            from.renameTo(to)
        }))
        val thread = Thread { try { repo.update { it.copy(deviceName = "nouveau") } } finally { returned.countDown() } }
        thread.start()
        try {
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("l'appelant attend le disque", returned.await(1, java.util.concurrent.TimeUnit.SECONDS))
        } finally { release.countDown(); thread.join(5000) }
    }

    @Test
    fun `un echec ordinaire est signale sans publication et la file continue`() = kotlinx.coroutines.runBlocking {
        val errors = ConcurrentLinkedQueue<Exception>()
        var attempts = 0
        val repo = LocalStateRepository(LocalStateStore(file(), rename = { from, to ->
            attempts++
            if (attempts == 1) false else from.renameTo(to)
        }), onSaveError = { errors.add(it) })
        val failed = repo.update { it.copy(deviceName = "perdu") }.await()
        assertTrue(failed.isFailure)
        assertEquals(1, errors.size)
        assertEquals(null, repo.state.value!!.deviceName)
        assertFalse(file().exists())
        val result = repo.update { it.copy(theme = ThemeChoice.DARK) }.await().getOrThrow()
        assertEquals(null, result.deviceName)
        assertEquals(result, LocalStateStore(file()).load())
    }

    @Test
    fun `les mises a jour ordinaires et durables restent ordonnees et ne perdent aucune mutation`() = kotlinx.coroutines.runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var calls = 0
        val repo = LocalStateRepository(LocalStateStore(file(), rename = { from, to ->
            if (++calls == 1) { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
            from.renameTo(to)
        }))
        val first = repo.update { it.copy(deviceName = "A") }
        assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(null, repo.state.value!!.deviceName)
        val second = repo.update { it.copy(deviceName = it.deviceName + "B") }
        val durable = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            repo.updateDurably { it.copy(transcriptionTasks = listOf(sampleTask())) }
        }
        val last = repo.update { it.copy(deviceName = it.deviceName + "C") }
        try { assertFalse(durable.isCompleted) } finally { release.countDown() }
        first.await().getOrThrow(); second.await().getOrThrow()
        assertEquals("AB", durable.await().deviceName)
        val result = last.await().getOrThrow()
        assertEquals("ABC", result.deviceName)
        assertEquals(listOf(sampleTask()), result.transcriptionTasks)
        assertEquals(result, repo.state.value)
        assertEquals(result, LocalStateStore(file()).load())
    }

    @Test
    fun `une erreur durable empeche son effet dependant et laisse passer les changements suivants`() = kotlinx.coroutines.runBlocking {
        var attempts = 0
        val repo = LocalStateRepository(LocalStateStore(file(), rename = { from, to ->
            attempts++
            if (attempts == 1) false else from.renameTo(to)
        }))
        var effect = false
        try {
            repo.updateDurably { it.copy(deviceName = "perdu") }
            effect = true
            org.junit.Assert.fail("la sauvegarde doit echouer")
        } catch (_: java.io.IOException) { }
        assertFalse(effect)
        assertEquals(null, repo.state.value!!.deviceName)
        val next = repo.updateDurably { it.copy(theme = ThemeChoice.DARK) }
        assertEquals(next, LocalStateStore(file()).load())
        assertEquals(null, next.deviceName)
    }
    @Test
    fun `les modifications ordinaires concurrentes sont toutes conservees`() = kotlinx.coroutines.runBlocking {
        val repo = LocalStateRepository(LocalStateStore(file()))
        val start = java.util.concurrent.CountDownLatch(1)
        val writes = ConcurrentLinkedQueue<kotlinx.coroutines.Deferred<Result<LocalState>>>()
        val threads = (0 until 16).map { i ->
            Thread {
                start.await()
                writes.add(repo.update { it.withError("N$i", Step.RECOVER, "erreur") })
            }
        }
        threads.forEach { it.start() }; start.countDown(); threads.forEach { it.join() }
        writes.forEach { it.await().getOrThrow() }
        val result = repo.updateDurably { it.copy(transcriptionTasks = listOf(sampleTask())) }
        assertEquals((0 until 16).map { "N$it" }.toSet(), result.errors.keys)
        assertEquals(result, LocalStateStore(file()).load())
    }

    @Test fun `lecture migre en memoire sans toucher au fichier`() {
        val store = LocalStateStore(file())
        val valid = sampleTask()
        store.save(LocalState(deviceName = "boitier", transcriptionTasks = listOf(valid, valid.copy(localId = "ancien"))))
        val old = org.json.JSONObject(file().readText())
        val secret = "synthetic-legacy-secret"
        val invalid = "https://user:$secret@example.invalid/v1?key=$secret"
        old.put("transcriptionSettings", org.json.JSONObject().put("baseUrl", invalid)
            .put("language", "en").put("model", "old-model").put("credentialRef", "kept-ref"))
        old.getJSONArray("transcriptionTasks").getJSONObject(1).put("serverAddress", invalid)
        file().writeText(old.toString())
        val before = file().readBytes()
        val loaded = store.load()
        assertTrue(before.contentEquals(file().readBytes()))
        assertFalse(File(tmp.root, "state.json.tmp").exists())
        assertFalse(File(tmp.root, "state.json.bad").exists())
        assertEquals("/v1", loaded.transcriptionSettings.baseUrl)
        assertEquals("en", loaded.transcriptionSettings.language)
        assertEquals("old-model", loaded.transcriptionSettings.model)
        assertEquals("kept-ref", loaded.transcriptionSettings.credentialRef)
        assertTrue(loaded.transcriptionAddressNeedsReview)
        assertEquals(valid, loaded.transcriptionTasks.first())
        val blocked = loaded.transcriptionTasks.last()
        assertEquals("", blocked.serverAddress)
        assertTrue(blocked.suspended)
        assertEquals(INVALID_TASK_ADDRESS_REASON, blocked.suspensionReason)
        assertEquals(valid.encryptedKeyRef, blocked.encryptedKeyRef)
        assertFalse(loaded.toString().contains(secret))
        store.save(loaded.copy(theme = ThemeChoice.DARK))
        assertFalse(file().readText().contains(secret))
        assertEquals(loaded.copy(theme = ThemeChoice.DARK), store.load())
    }

    @Test fun `migration des adresses conserve les phases et les champs terminaux`() {
        val store = LocalStateStore(file())
        val phases = TranscriptionPhase.entries
        val originals = phases.mapIndexed { index, phase ->
            sampleTask().copy(localId = "phase-$index", phase = phase, serverId = "server-$index",
                progress = 0.6, error = "erreur conservée", suspended = phase == TranscriptionPhase.FAILED,
                suspensionReason = if (phase == TranscriptionPhase.FAILED) "ancien motif" else null)
        }
        store.save(LocalState(transcriptionTasks = originals))
        val old = org.json.JSONObject(file().readText())
        val secret = "synthetic-address-migration-secret"
        val tasks = old.getJSONArray("transcriptionTasks")
        phases.forEachIndexed { i, _ ->
            tasks.getJSONObject(i).put("serverAddress", if (i % 2 == 0) "https://example.invalid" else "https://user:$secret@example.invalid/v1")
        }
        file().writeText(old.toString())
        val before = file().readText()
        val loaded = store.load()
        assertEquals(before, file().readText())
        val terminal = setOf(TranscriptionPhase.COMPLETED, TranscriptionPhase.FAILED, TranscriptionPhase.CANCELLED)
        originals.zip(loaded.transcriptionTasks).forEach { (original, migrated) ->
            val expected = if (original.phase in terminal) original.copy(serverAddress = "")
                else original.copy(serverAddress = "", suspended = true, suspensionReason = INVALID_TASK_ADDRESS_REASON)
            assertEquals(original.phase.name, expected, migrated)
        }
        assertFalse(loaded.toString().contains(secret))
        val messages = transcriptionTaskSettingsMessages(loaded.transcriptionTasks)
        assertEquals(phases.size - terminal.size, messages.size)
        assertTrue(messages.all { it.contains("crée une nouvelle tâche") })
        store.save(loaded.copy(theme = ThemeChoice.DARK))
        assertFalse(file().readText().contains(secret))
        assertEquals(loaded.copy(theme = ThemeChoice.DARK), store.load())
    }

    @Test fun `une ancienne suspension erronee de migration terminale est retiree`() {
        val store = LocalStateStore(file())
        val phases = listOf(TranscriptionPhase.COMPLETED, TranscriptionPhase.FAILED, TranscriptionPhase.CANCELLED)
        val tasks = phases.map { sampleTask().copy(localId = it.name, phase = it) }
        store.save(LocalState(transcriptionTasks = tasks))
        val old = org.json.JSONObject(file().readText())
        val array = old.getJSONArray("transcriptionTasks")
        phases.forEachIndexed { i, _ ->
            array.getJSONObject(i).put("serverAddress", "").put("suspended", true)
                .put("suspensionReason", INVALID_TASK_ADDRESS_REASON)
        }
        file().writeText(old.toString())
        val migrated = store.load()
        assertEquals(tasks.map { it.copy(serverAddress = "") }, migrated.transcriptionTasks)
        store.save(migrated)
        assertEquals(migrated, store.load())
    }

}
