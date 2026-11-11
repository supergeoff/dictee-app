package top.supergeoff.dictee

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
    fun `un etat illisible donne un etat vide et reste de cote`() {
        file().writeText("""{"version":2,"deviceAdd""")
        assertEquals(LocalState(), LocalStateStore(file()).load())
        assertTrue(File(tmp.root, "state.json.bad").exists())
    }

    @Test
    fun `un etat qu'on ne peut pas lire donne un etat vide et reste de cote`() {
        file().mkdir()   // lire un dossier lève une IOException, pas une JSONException
        assertEquals(LocalState(), LocalStateStore(file()).load())
        assertTrue(File(tmp.root, "state.json.bad").isDirectory)
        assertFalse(file().exists())
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
                progress = if (i % 2 == 0) i * 10 else null, error = if (i == 7) "erreur serveur" else null,
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
        assertEquals("ancien", repository.state.value.deviceName)
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
}
