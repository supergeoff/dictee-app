package top.supergeoff.dictee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

class SendJobTest {
    @get:Rule val tmp = TemporaryFolder()
    private val destination = DriveDestination("test@example.invalid", "F1")
    private val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val n = "20261030084752"
    private val defaultFolder = DriveFolder("F1", "Dictée")
    private fun json(code: Int, body: String, headers: Map<String, String> = emptyMap()) = HttpResponse(code, body, headers)
    private fun recording(store: MemoryStore, vararg files: String): Recording {
        files.forEach { store.files[it] = "abc".toByteArray() }
        val phone = files.associate { f -> RecordName.parse(f)!!.kind to PhoneFile(f, 3) }
        return Recording(n, RecordType.CALL, 3, true, phone, DisplayState.Recovered)
    }
    private fun run(
        http: Http, store: MemoryStore, rs: List<Recording>, log: ListLog = ListLog(), labels: MutableList<String> = mutableListOf(),
        explicit: Boolean = false, choice: Set<FileKind> = FileKind.entries.toSet(),
        receipts: List<DriveReceipt> = listOf(DriveReceipt(destination, "Call-$n.vtt", "A2", hash)),
    ) = runBlocking { SendJob(DriveClient(http, FakeTokens("t1", "t2")), store, log, tmp.root).run(defaultFolder, rs, choice, destination, receipts, { _, p -> labels += p.label() }, {}, explicitSelection = explicit, beforeUpdate = { _, _ -> }) }

    @Test fun uncertainUpdateInvalidatesOldReceiptDurably() = runBlocking {
        for (wrongSize in listOf(true, false)) {
            val file = "Call-$n.vtt"
            val stateFile = File(tmp.newFolder(), "state.json")
            val repository = LocalStateRepository(LocalStateStore(stateFile))
            val old = DriveReceipt(destination, file, "A2", hash)
            repository.updateDurably { it.withDriveReceipt(old) }
            val store = MemoryStore(); val rec = recording(store, file)
            store.files[file] = "abd".toByteArray()
            val http = FakeHttp(json(200, """{"files":[{"id":"A2","name":"$file","size":"3"}]}"""),
                json(200, "", mapOf("Location" to "https://upload.example/s1")),
                json(200, """{"id":"A2","name":"$file","size":"${if (wrongSize) 2 else 3}"}"""))
            val out = SendJob(DriveClient(http, FakeTokens("t1", "t2")), store, ListLog(), tmp.root)
                .run(defaultFolder, listOf(rec), setOf(FileKind.VTT, FileKind.MD), destination, listOf(old), { _, _ -> },
                    { result -> if (result is SendResult.Sent) throw java.io.IOException("receipt failure") },
                    beforeUpdate = { dest, name -> repository.updateDurably { it.withoutDriveReceipt(dest, name) } })
            assertTrue(if (wrongSize) out.results.single() is SendResult.Failed else out is SendOutcome.Stopped)
            val reloaded = LocalStateStore(stateFile).load()
            assertTrue(reloaded.driveReceipts.isEmpty())
            assertEquals(DriveWriteAction.UPDATE, DriveSync.action(file, FileKind.VTT, hash, destination,
                listOf(DriveFile("A2", file, 3)), reloaded.driveReceipts))
        }
    }

    @Test fun failedInvalidationStopsBeforeUpdateNetwork() = runBlocking {
        val file = "Call-$n.vtt"; val store = MemoryStore(); val rec = recording(store, file)
        val http = FakeHttp(json(200, """{"files":[{"id":"A2","name":"$file","size":"3"}]}"""))
        val out = SendJob(DriveClient(http, FakeTokens("t1", "t2")), store, ListLog(), tmp.root)
            .run(defaultFolder, listOf(rec), setOf(FileKind.VTT, FileKind.MD), destination, emptyList(), { _, _ -> }, {},
                beforeUpdate = { _, _ -> throw java.io.IOException("disk failure") })
        assertTrue(out is SendOutcome.Stopped)
        assertEquals(1, http.requests.size)
    }

    @Test fun existingSendErrorSurvivesFirstSuccessThenReceiptFailure() = runBlocking {
        val store = MemoryStore(); val mp3 = "Call-$n.mp3"; val vtt = "Call-$n.vtt"
        val rec = recording(store, mp3, vtt)
        val http = FakeHttp(json(200, """{"files":[{"id":"A","name":"$mp3","size":"3"}]}"""),
            json(200, "", mapOf("Location" to "https://upload.example/s1")),
            json(200, """{"id":"V1","name":"$vtt","size":"3"}"""))
        val initial = LocalState().withError(n, Step.SEND, "ancienne erreur")
        val out = SendJob(DriveClient(http, FakeTokens("t1", "t2")), store, ListLog(), tmp.root)
            .run(defaultFolder, listOf(rec), FileKind.entries.toSet(), destination, emptyList(), { _, _ -> }, { result ->
                if (result is SendResult.Sent) throw java.io.IOException("disque indisponible")
            })
        assertTrue(out is SendOutcome.Stopped)
        assertTrue(out.results.single() is SendResult.AlreadyThere)
        assertEquals("ancienne erreur", applySendOutcome(initial, out).errors[n]?.get(Step.SEND))
    }

    @Test fun completedSendClearsOnlyRecordingsWithoutFailures() {
        val initial = LocalState().withError(n, Step.SEND, "ancienne erreur")
        val success = SendResult.AlreadyThere(n, "Call-$n.mp3")
        assertTrue(applySendOutcome(initial, SendOutcome.Done(listOf(success))).errors[n]?.get(Step.SEND) == null)
        val failure = SendResult.Failed(n, "Call-$n.vtt", "nouvelle erreur")
        assertEquals("nouvelle erreur", applySendOutcome(initial, SendOutcome.Done(listOf(success, failure))).errors[n]?.get(Step.SEND))
    }

    @Test fun receiptPersistenceFailureStopsBeforeNextFile() = runBlocking {
        val store = MemoryStore(); val vtt = "Call-$n.vtt"; val md = "Call-$n.md"
        val rec = recording(store, vtt, md)
        val http = FakeHttp(json(200, """{"files":[]}"""), json(200, "", mapOf("Location" to "https://upload.example/s1")),
            json(200, """{"id":"V1","name":"$vtt","size":"3"}"""))
        val out = SendJob(DriveClient(http, FakeTokens("t1", "t2")), store, ListLog(), tmp.root)
            .run(defaultFolder, listOf(rec), setOf(FileKind.VTT, FileKind.MD), destination, emptyList(), { _, _ -> },
                { throw java.io.IOException("disque indisponible") })
        assertTrue(out is SendOutcome.Stopped)
        assertTrue(out.results.isEmpty())
        assertEquals(3, http.requests.size)
    }

    @Test fun receiptKeepsCapturedDestinationWhenSettingsChangeDuringUpload() = runBlocking {
        val repository = LocalStateRepository(LocalStateStore(File(tmp.root, "state.json")))
        repository.updateDurably { it.copy(driveAccount = destination.account, chosenFolderId = destination.folderId) }
        val store = MemoryStore(); val vtt = "Call-$n.vtt"
        val rec = recording(store, vtt)
        val http = FakeHttp(json(200, """{"files":[]}"""), json(200, "", mapOf("Location" to "https://upload.example/s1")),
            json(200, """{"id":"V1","name":"$vtt","size":"3"}"""))
        val out = SendJob(DriveClient(http, FakeTokens("t1", "t2")), store, ListLog(), tmp.root)
            .run(defaultFolder, listOf(rec), setOf(FileKind.VTT, FileKind.MD), destination, emptyList(), { _, _ -> }, { result ->
                repository.updateDurably { it.copy(driveAccount = "other@example.invalid", chosenFolderId = "other") }
                if (result is SendResult.Sent && result.receipt != null) repository.updateDurably { it.withDriveReceipt(result.receipt) }
            })
        assertTrue(out is SendOutcome.Done)
        val state = LocalStateStore(File(tmp.root, "state.json")).load()
        assertEquals("other", state.chosenFolderId)
        assertEquals(destination, state.driveReceipts.single().destination)
    }

    @Test fun failedDurableReceiptStopsBatchWithoutPublishingReceipt() = runBlocking {
        val repository = LocalStateRepository(LocalStateStore(tmp.newFile("state.json"), rename = { _, _ -> false }), load = { LocalState() })
        val store = MemoryStore(); val vtt = "Call-$n.vtt"; val md = "Call-$n.md"
        val rec = recording(store, vtt, md)
        val http = FakeHttp(json(200, """{"files":[]}"""), json(200, "", mapOf("Location" to "https://upload.example/s1")),
            json(200, """{"id":"V1","name":"$vtt","size":"3"}"""))
        val out = SendJob(DriveClient(http, FakeTokens("t1", "t2")), store, ListLog(), tmp.root)
            .run(defaultFolder, listOf(rec), setOf(FileKind.VTT, FileKind.MD), destination, emptyList(), { _, _ -> }, { result ->
                if (result is SendResult.Sent && result.receipt != null) repository.updateDurably { it.withDriveReceipt(result.receipt) }
            })
        assertTrue(out is SendOutcome.Stopped)
        assertTrue(out.results.isEmpty())
        assertTrue(repository.state.value!!.driveReceipts.isEmpty())
        assertEquals(3, http.requests.size)
    }

    @Test fun selectedMdSendsOnlyMdAndEmptySelectionSendsNothing() {
        val store = MemoryStore()
        val rec = recording(store, "Call-$n.vtt", "Call-$n.md")
        val md = "Call-$n.md"
        val http = FakeHttp(json(200, """{"files":[]}"""), json(200, "", mapOf("Location" to "https://upload.example/s1")),
            json(200, """{"id":"M1","name":"$md","size":"3"}"""))
        val out = run(http, store, listOf(rec), choice = setOf(FileKind.MD))
        assertEquals(listOf(md), out.results.map { it.fileName })
        val empty = run(FakeHttp(json(200, """{"files":[]}""")), store, listOf(rec), choice = emptySet())
        assertTrue(empty.results.isEmpty())
    }

    @Test fun documentsOnlySkipsAudioAndAbsentDocuments() {
        val store = MemoryStore(); val http = FakeHttp(json(200, """{"files":[]}"""))
        val out = run(http, store, listOf(recording(store, "Call-$n.mp3")), choice = setOf(FileKind.VTT, FileKind.MD))
        assertTrue(out.results.isEmpty()); assertEquals(1, http.requests.size)
    }

    @Test fun unprovenDocumentIsUpdatedAndReceipted() {
        val store = MemoryStore(); val file = "Call-$n.vtt"
        val http = FakeHttp(json(200, """{"files":[{"id":"A2","name":"$file","size":"3"}]}"""),
            json(200, "", mapOf("Location" to "https://upload.example/s1")), json(200, """{"id":"A2","name":"$file","size":"3"}"""))
        val out = run(http, store, listOf(recording(store, file)), receipts = emptyList())
        assertEquals("PATCH", http.requests[1].method)
        assertEquals(DriveReceipt(destination, file, "A2", hash), (out.results.single() as SendResult.Sent).receipt)
    }

    @Test fun uploadReadsSnapshotEvenWhenSourceChanges() {
        val store = MemoryStore(); val file = "Call-$n.vtt"
        val rec = recording(store, file)
        val inner = FakeHttp(json(200, """{"files":[]}"""), json(200, "", mapOf("Location" to "https://upload.example/s1")),
            json(200, """{"id":"V1","name":"$file","size":"3"}"""))
        var uploaded = ""
        val http = object : Http {
            override fun call(request: HttpRequest): HttpResponse {
                if (request.method == "PUT") {
                    store.files[file] = "abd".toByteArray()
                    uploaded = (request.body as HttpBody.Stream).open().bufferedReader().use { it.readText() }
                }
                return inner.call(request)
            }
        }
        val sent = run(http, store, listOf(rec), receipts = emptyList()).results.single() as SendResult.Sent
        assertEquals("abc", uploaded); assertEquals(hash, sent.receipt!!.sha256)
        assertTrue(tmp.root.listFiles()!!.isEmpty())
    }

    @Test fun duplicateDocumentFailsWithoutUpload() {
        val store = MemoryStore(); val file = "Call-$n.vtt"
        val http = FakeHttp(json(200, """{"files":[{"id":"A2","name":"$file","size":"3"},{"id":"A3","name":"$file","size":"3"}]}"""))
        val out = run(http, store, listOf(recording(store, file)), receipts = emptyList())
        assertTrue(out.results.single() is SendResult.Failed); assertEquals(1, http.requests.size)
    }

    @Test fun partialSuccessIsRetainedAndRetrySkipsReceipt() {
        val store = MemoryStore(); val vtt = "Call-$n.vtt"; val md = "Call-$n.md"
        val rec = recording(store, vtt, md)
        val http = FakeHttp(json(200, """{"files":[]}"""), json(200, "", mapOf("Location" to "https://upload.example/s1")),
            json(200, """{"id":"V1","name":"$vtt","size":"3"}"""), json(403, """{"error":{"message":"Forbidden"}}"""))
        val out = run(http, store, listOf(rec), receipts = emptyList())
        val receipt = (out.results[0] as SendResult.Sent).receipt!!
        assertTrue(out.results[1] is SendResult.Failed)
        val retry = FakeHttp(json(200, """{"files":[{"id":"V1","name":"$vtt","size":"3"}]}"""),
            json(200, "", mapOf("Location" to "https://upload.example/s2")), json(200, """{"id":"M1","name":"$md","size":"2"}"""))
        val again = run(retry, store, listOf(rec), receipts = listOf(receipt))
        assertTrue(again.results[0] is SendResult.AlreadyThere); assertTrue(again.results[1] is SendResult.Failed)
        assertEquals(3, retry.requests.size)
    }

    @Test
    fun `le MP3 au nom de la 0_1_5 compte comme present`() {
        val store = MemoryStore(); val http = FakeHttp(json(200, """{"files":[{"id":"A1","name":"$n.mp3","size":"3"}]}"""))
        val out = run(http, store, listOf(recording(store, "Call-$n.mp3")))
        assertEquals(listOf(SendResult.AlreadyThere(n, "Call-$n.mp3")), out.results); assertEquals(1, http.requests.size)
    }

    @Test
    fun `un fichier manquant part avec son type et sa taille controlee`() {
        val store = MemoryStore(); val labels = mutableListOf<String>()
        val http = FakeHttp(json(200, """{"files":[]}"""), json(200, "", mapOf("Location" to "https://upload.example/s1")), json(200, """{"id":"X1","name":"fichier","size":"3"}"""))
        val out = run(http, store, listOf(recording(store, "Call-$n.mp3")), labels = labels)
        assertEquals(SendOutcome.Done(listOf(SendResult.Sent(n, "Call-$n.mp3"))), out)
        assertEquals("audio/mpeg", http.requests[1].headers["X-Upload-Content-Type"])
        assertEquals("Envoi 1/1 · 0 %", labels.first()); assertEquals("Envoi 1/1 · 100 %", labels.last())
    }

    @Test
    fun `une taille differente est une erreur`() {
        val store = MemoryStore()
        val http = FakeHttp(json(200, """{"files":[]}"""), json(200, "", mapOf("Location" to "https://upload.example/s1")), json(200, """{"id":"X1","name":"fichier","size":"2"}"""))
        assertEquals(listOf(SendResult.Failed(n, "Call-$n.mp3", "taille Drive 2 au lieu de 3")), run(http, store, listOf(recording(store, "Call-$n.mp3"))).results)
    }

    @Test
    fun `un VTT deja present n'est pas envoye et une selection explicite le dit fichier par fichier`() {
        val store = MemoryStore(); val log = ListLog()
        val http = FakeHttp(json(200, """{"files":[{"id":"A2","name":"Call-$n.vtt","size":"10"}]}"""),
            json(200, "", mapOf("Location" to "https://upload.example/s1")), json(200, """{"id":"X1","name":"fichier","size":"3"}"""))
        val out = run(http, store, listOf(recording(store, "Call-$n.mp3", "Call-$n.vtt")), log, explicit = true)
        assertEquals(listOf(SendResult.Sent(n, "Call-$n.mp3"), SendResult.AlreadyThere(n, "Call-$n.vtt")), out.results)
        assertTrue(log.lines.contains("DRIVE Call-$n.vtt déjà dans Dictée, non envoyé"))
        assertTrue(log.lines.none { it.contains("fichier déjà") || it.contains("fichiers déjà") })
    }

    @Test
    fun `sans selection explicite, les fichiers deja presents tiennent en une seule ligne`() {
        val store = MemoryStore(); val log = ListLog()
        val http = FakeHttp(json(200, """{"files":[{"id":"A1","name":"Call-$n.mp3","size":"3"},{"id":"A2","name":"Call-$n.vtt","size":"10"}]}"""),
            json(200, "", mapOf("Location" to "https://upload.example/s1")), json(200, """{"id":"X1","name":"fichier","size":"3"}"""))
        val out = run(http, store, listOf(recording(store, "Call-$n.mp3", "Call-$n.vtt", "Call-$n.md")), log)
        assertEquals(
            listOf(SendResult.AlreadyThere(n, "Call-$n.mp3"), SendResult.AlreadyThere(n, "Call-$n.vtt"), SendResult.Sent(n, "Call-$n.md", DriveReceipt(destination, "Call-$n.md", "X1", hash))),
            out.results,
        )
        assertEquals(listOf("DRIVE 2 fichiers déjà dans Dictée, non envoyés"), log.lines.filter { "déjà" in it })
    }

    @Test
    fun `la ligne des fichiers deja presents s'accorde au singulier et manque quand aucun n'est present`() {
        val store = MemoryStore(); val log = ListLog()
        val one = FakeHttp(json(200, """{"files":[{"id":"A2","name":"Call-$n.vtt","size":"10"}]}"""),
            json(200, "", mapOf("Location" to "https://upload.example/s1")), json(200, """{"id":"X1","name":"fichier","size":"3"}"""))
        run(one, store, listOf(recording(store, "Call-$n.mp3", "Call-$n.vtt")), log)
        assertEquals(listOf("DRIVE 1 fichier déjà dans Dictée, non envoyé"), log.lines.filter { "déjà" in it })
        val none = FakeHttp(json(200, """{"files":[]}"""),
            json(200, "", mapOf("Location" to "https://upload.example/s1")), json(200, """{"id":"X1","name":"fichier","size":"3"}"""))
        val quiet = ListLog()
        run(none, store, listOf(recording(store, "Call-$n.mp3")), quiet)
        assertTrue(quiet.lines.none { "déjà" in it })
    }

    @Test
    fun `un acces Google a renouveler arrete l'envoi`() {
        val store = MemoryStore()
        val first = recording(store, "Call-$n.mp3")
        store.files["Call-20261031094837.mp3"] = "abc".toByteArray()
        val second = first.copy(name = "20261031094837", phoneFiles = mapOf(FileKind.MP3 to PhoneFile("Call-20261031094837.mp3", 3)))
        val http = FakeHttp(json(200, """{"files":[]}"""), json(401, "{}"), json(401, "{}"))
        val out = run(http, store, listOf(first, second))
        assertEquals("Accès Google à renouveler", (out as SendOutcome.Stopped).reason); assertEquals(3, http.requests.size)
    }

    @Test
    fun `un dossier inaccessible annule l'envoi`() {
        val store = MemoryStore()
        val http = FakeHttp(json(404, """{"error":{"message":"File not found: F1."}}"""))
        val out = run(http, store, listOf(recording(store, "Call-$n.mp3")))
        assertTrue((out as SendOutcome.Stopped).reason.startsWith("Dossier Drive inaccessible : ")); assertEquals(1, http.requests.size)
    }

    @Test
    fun `le dossier choisi est controle et jamais remplace`() {
        val ok = FakeHttp(json(200, """{"id":"P1","name":"Comptes rendus","mimeType":"application/vnd.google-apps.folder","trashed":false,"capabilities":{"canAddChildren":true}}"""))
        val state = LocalState(knownFolders = listOf(KnownFolder("P1", "Comptes rendus", FolderOrigin.PICKED)), chosenFolderId = "P1")
        assertEquals(DriveFolder("P1", "Comptes rendus"), resolveFolder(state, DriveClient(ok, FakeTokens("t"))).first)
        val trashed = FakeHttp(json(200, """{"id":"P1","name":"Comptes rendus","mimeType":"application/vnd.google-apps.folder","trashed":true,"capabilities":{"canAddChildren":true}}"""))
        assertThrows(DriveException::class.java) { resolveFolder(state, DriveClient(trashed, FakeTokens("t"))) }
        assertEquals(1, trashed.requests.size)
    }

    @Test
    fun `sans dossier choisi, Dictee est cherche puis cree a la racine`() {
        val found = FakeHttp(json(200, """{"files":[{"id":"M0","name":"Dictée"}]}"""))
        val (f1, s1) = resolveFolder(LocalState(), DriveClient(found, FakeTokens("t")))
        assertEquals(DriveFolder("M0", "Dictée"), f1); assertEquals("M0", s1.chosenFolderId)
        assertEquals(listOf(KnownFolder("M0", "Dictée", FolderOrigin.CREATED)), s1.knownFolders)
        val none = FakeHttp(json(200, """{"files":[]}"""), json(200, """{"id":"M1","name":"Dictée"}"""))
        val (f2, s2) = resolveFolder(LocalState(), DriveClient(none, FakeTokens("t")))
        assertEquals(DriveFolder("M1", "Dictée"), f2); assertEquals("M1", s2.chosenFolderId); assertEquals("POST", none.requests[1].method)
    }

    @Test
    fun `une erreur sur un fichier n'arrete pas la file`() {
        val store = MemoryStore(); val labels = mutableListOf<String>(); val log = ListLog()
        val first = recording(store, "Call-$n.mp3")
        store.files["Call-20261031094837.mp3"] = "abc".toByteArray()
        val second = first.copy(name = "20261031094837", phoneFiles = mapOf(FileKind.MP3 to PhoneFile("Call-20261031094837.mp3", 3)))
        val http = FakeHttp(json(200, """{"files":[]}"""), json(500, """{"error":{"message":"Backend Error"}}"""),
            json(200, "", mapOf("Location" to "https://upload.example/s2")), json(200, """{"id":"X1","name":"fichier","size":"3"}"""))
        val out = run(http, store, listOf(first, second), log, labels)
        assertEquals(
            SendOutcome.Done(listOf(SendResult.Failed(n, "Call-$n.mp3", "Drive a répondu 500 : Backend Error"), SendResult.Sent("20261031094837", "Call-20261031094837.mp3"))),
            out,
        )
        assertTrue(log.lines.contains("DRIVE erreur : Call-$n.mp3 : Drive a répondu 500 : Backend Error"))
        assertEquals(listOf("Envoi 1/2 · 0 %", "Envoi 2/2 · 0 %", "Envoi 2/2 · 100 %"), labels)
    }

    @Test
    fun `le pourcentage suit les octets envoyes`() {
        val store = MemoryStore(); val labels = mutableListOf<String>()
        val http = ReadingHttp(FakeHttp(json(200, """{"files":[]}"""), json(200, "", mapOf("Location" to "https://upload.example/s1")), json(200, """{"id":"X1","name":"fichier","size":"3"}""")))
        run(http, store, listOf(recording(store, "Call-$n.mp3")), labels = labels)
        assertEquals(listOf("Envoi 1/1 · 0 %", "Envoi 1/1 · 33 %", "Envoi 1/1 · 66 %", "Envoi 1/1 · 100 %"), labels.distinct())
    }

    @Test
    fun `un fichier absent du telephone echoue sans arreter l'envoi`() {
        val store = MemoryStore()
        val lost = recording(store, "Call-$n.mp3")
        store.files.clear()
        val http = ReadingHttp(FakeHttp(json(200, """{"files":[]}"""), json(200, "", mapOf("Location" to "https://upload.example/s1"))))
        assertEquals(SendOutcome.Done(listOf(SendResult.Failed(n, "Call-$n.mp3", "Call-$n.mp3 absent"))), run(http, store, listOf(lost)))
    }

    @Test
    fun `la cause technique d'un dossier illisible va au journal, pas au resultat`() {
        val log = ListLog()
        val store = object : PhoneStore {
            override fun delete(fileName: String) { throw PhoneStoreException("Non utilisé") }
            override fun list() = emptyList<PhoneFile>()
            override fun write(fileName: String, source: File) {}
            override fun writeGuarded(fileName: String, source: File, replace: Boolean, promote: ((() -> Unit) -> Unit)) { throw PhoneStoreException("Non utilisé") }
            override fun open(fileName: String): InputStream =
                throw PhoneStoreException("Dossier du téléphone inaccessible.", FileNotFoundException("dossier illisible"))
        }
        val http = ReadingHttp(FakeHttp(json(200, """{"files":[]}"""), json(200, "", mapOf("Location" to "https://upload.example/s1"))))
        val lost = Recording(n, RecordType.CALL, 3, true, mapOf(FileKind.MP3 to PhoneFile("Call-$n.mp3", 3)), DisplayState.Recovered)
        val out = runBlocking { SendJob(DriveClient(http, FakeTokens("t1", "t2")), store, log, tmp.root).run(defaultFolder, listOf(lost), FileKind.entries.toSet(), destination, emptyList(), { _, _ -> }, {}) }
        assertEquals(SendOutcome.Done(listOf(SendResult.Failed(n, "Call-$n.mp3", "Dossier du téléphone inaccessible."))), out)
        assertTrue(log.lines.contains("DRIVE erreur : Call-$n.mp3 : Dossier du téléphone inaccessible. (cause : FileNotFoundException : dossier illisible)"))
    }

    @Test
    fun `un Dictee connu et valide est repris sans recherche`() {
        val http = FakeHttp(json(200, """{"id":"K1","name":"Dictée","mimeType":"application/vnd.google-apps.folder","trashed":false,"capabilities":{"canAddChildren":true}}"""))
        val state = LocalState(knownFolders = listOf(KnownFolder("K1", "Dictée", FolderOrigin.CREATED)))
        val (folder, next) = resolveFolder(state, DriveClient(http, FakeTokens("t")))
        assertEquals(DriveFolder("K1", "Dictée"), folder); assertEquals("K1", next.chosenFolderId)
        assertEquals(state.knownFolders, next.knownFolders); assertEquals(1, http.requests.size)
    }

    @Test
    fun `un Dictee connu mais inutilisable est ignore au profit de celui de Drive`() {
        val http = FakeHttp(
            json(200, """{"id":"K1","name":"Dictée","mimeType":"application/vnd.google-apps.folder","trashed":true,"capabilities":{"canAddChildren":true}}"""),
            json(200, """{"files":[{"id":"M2","name":"Dictée"}]}"""),
        )
        val state = LocalState(knownFolders = listOf(KnownFolder("K1", "Dictée", FolderOrigin.CREATED)))
        val (folder, next) = resolveFolder(state, DriveClient(http, FakeTokens("t")))
        assertEquals(DriveFolder("M2", "Dictée"), folder); assertEquals("M2", next.chosenFolderId)
        assertEquals(listOf(KnownFolder("K1", "Dictée", FolderOrigin.CREATED), KnownFolder("M2", "Dictée", FolderOrigin.CREATED)), next.knownFolders)
    }
}

/** Faux client HTTP qui lit le corps d'un envoi octet par octet, comme [UrlHttp] le fait bloc par bloc, puis répond comme [inner]. */
private class ReadingHttp(private val inner: FakeHttp) : Http {
    override fun call(request: HttpRequest): HttpResponse {
        val body = request.body
        if (body is HttpBody.Stream) {
            var sent = 0L
            body.open().use { input -> while (input.read() >= 0) body.onProgress(++sent) }
        }
        return inner.call(request)
    }
}
