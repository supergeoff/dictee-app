package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Test

class DriveSendRequestTest {
    private val destination = DriveDestination("account@example.invalid", "folder")
    private val name = "20261111120000"
    private val files = listOf(FileKind.VTT, FileKind.MD).associateWith { PhoneFile("Note-$name.${it.ext}", 4) }
    private val recording = Recording(name, RecordType.NOTE, 4, false, files, DisplayState.Ready)
    private val remote = files.values.mapIndexed { i, file -> DriveFile("id$i", file.fileName, file.size) }
    private val hashes = files.values.associate { it.fileName to "hash" }
    private val receipts = remote.map { DriveReceipt(destination, it.name, it.id, "hash") }

    @Test fun documentsRequireCurrentReceiptsAndAudioOnlyForAllFiles() {
        fun complete(r: Recording = recording, choice: DriveSendChoice = DriveSendChoice.DOCUMENTS_ONLY,
                     h: Map<String, String> = hashes, proof: List<DriveReceipt> = receipts) =
            DriveSync.isComplete(r, choice, destination, remote, h, proof)
        assertTrue(complete())
        assertFalse(complete(choice = DriveSendChoice.ALL_FILES))
        assertFalse(complete(recording.copy(phoneFiles = files - FileKind.VTT)))
        assertFalse(complete(recording.copy(phoneFiles = files - FileKind.MD)))
        assertFalse(complete(h = hashes + (files.getValue(FileKind.VTT).fileName to "changed")))
        assertFalse(complete(proof = receipts.drop(1)))
    }

    @Test fun `cancel and duplicate confirmation consume request only once`() {
        val requests = DriveSendRequests()
        val names = mutableListOf("one")
        requests.open(names, DriveSendChoice.ALL_FILES)
        names.add("two")
        assertEquals(listOf("one"), requests.current?.names)
        requests.cancel()
        assertNull(requests.confirm(DriveSendChoice.DOCUMENTS_ONLY))
        requests.open(listOf("one"), DriveSendChoice.ALL_FILES)
        assertEquals(DriveSendRequest(listOf("one"), DriveSendChoice.DOCUMENTS_ONLY), requests.confirm(DriveSendChoice.DOCUMENTS_ONLY))
        assertNull(requests.confirm(DriveSendChoice.ALL_FILES))
    }

    @Test fun `empty selection cannot open`() {
        val requests = DriveSendRequests()
        requests.open(emptyList(), DriveSendChoice.ALL_FILES)
        assertNull(requests.current)
    }
    @Test fun missingDocumentsIncludeUnsendableSelection() {
        val audio = recording.copy(phoneFiles = mapOf(FileKind.MP3 to PhoneFile("Note-$name.mp3", 4)))
        val only = DriveSync.sendSummary(listOf(audio), DriveSendChoice.DOCUMENTS_ONLY)
        assertEquals(0, only.sendable.size)
        assertEquals(1, only.missingVtt)
        assertEquals(1, only.missingMd)
        val mixed = DriveSync.sendSummary(listOf(audio, recording), DriveSendChoice.DOCUMENTS_ONLY)
        assertEquals(listOf(recording), mixed.sendable)
        assertEquals(1, mixed.missingVtt)
        assertEquals(1, mixed.missingMd)
    }

    @Test fun selectionClearsOnlyOnConfirmation() {
        val requests = DriveSendRequests()
        var clears = 0
        requests.open(listOf(name), DriveSendChoice.ALL_FILES) { clears++ }
        requests.cancel()
        assertEquals(0, clears)
        requests.open(listOf(name), DriveSendChoice.ALL_FILES) { clears++ }
        requests.confirm(DriveSendChoice.DOCUMENTS_ONLY)
        requests.confirm(DriveSendChoice.ALL_FILES)
        assertEquals(1, clears)
    }

    @Test fun capturedAccountDrivesActualTokenSourceAfterSettingsChange() {
        var settings = LocalState(driveAccount = "first@example.invalid")
        val captured = settings
        val accounts = mutableListOf<String>()
        val http = FakeHttp(HttpResponse(200, """{"files":[]}""", emptyMap()))
        val client = driveClientForCapturedAccount(http, captured.driveAccount!!) { account ->
            accounts += account
            FakeTokens(account, account)
        }
        settings = settings.copy(driveAccount = "second@example.invalid")
        client.listEntries("folder")
        assertEquals("second@example.invalid", settings.driveAccount)
        assertEquals(listOf("first@example.invalid"), accounts)
        assertEquals("Bearer first@example.invalid", http.requests.single().headers["Authorization"])
    }
}
