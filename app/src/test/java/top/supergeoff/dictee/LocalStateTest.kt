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
        assertTrue(file().readText().contains("\"version\": 3"))
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

}
