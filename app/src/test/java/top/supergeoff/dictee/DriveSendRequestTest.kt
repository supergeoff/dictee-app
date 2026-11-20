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
        fun complete(r: Recording = recording, choice: Set<FileKind> = setOf(FileKind.VTT, FileKind.MD),
                     h: Map<String, String> = hashes, proof: List<DriveReceipt> = receipts) =
            DriveSync.isComplete(r, choice, destination, remote, h, proof)
        assertTrue(complete())
        assertFalse(complete(choice = FileKind.entries.toSet()))
        assertTrue(complete(recording.copy(phoneFiles = files - FileKind.VTT)))
        assertTrue(complete(recording.copy(phoneFiles = files - FileKind.MD)))
        assertFalse(complete(h = hashes + (files.getValue(FileKind.VTT).fileName to "changed")))
        assertFalse(complete(proof = receipts.drop(1)))
    }

    @Test fun `cancel and duplicate confirmation consume request only once`() {
        val requests = DriveSendRequests()
        val names = mutableListOf("one")
        requests.open(names, FileKind.entries.toSet())
        names.add("two")
        assertEquals(listOf("one"), requests.current?.names)
        requests.cancel()
        assertNull(requests.confirm(setOf(FileKind.VTT, FileKind.MD)))
        requests.open(listOf("one"), FileKind.entries.toSet())
        assertEquals(DriveSendRequest(listOf("one"), setOf(FileKind.VTT, FileKind.MD)), requests.confirm(setOf(FileKind.VTT, FileKind.MD)))
        assertNull(requests.confirm(FileKind.entries.toSet()))
    }

    @Test fun `empty selection cannot open`() {
        val requests = DriveSendRequests()
        requests.open(emptyList(), FileKind.entries.toSet())
        assertNull(requests.current)
    }
    @Test fun missingDocumentsIncludeUnsendableSelection() {
        val audio = recording.copy(phoneFiles = mapOf(FileKind.MP3 to PhoneFile("Note-$name.mp3", 4)))
        val only = DriveSync.sendSummary(listOf(audio), setOf(FileKind.VTT, FileKind.MD))
        assertEquals(0, only.sendable.size)
        assertEquals(1, only.missingVtt)
        assertEquals(1, only.missingMd)
        val mixed = DriveSync.sendSummary(listOf(audio, recording), setOf(FileKind.VTT, FileKind.MD))
        assertEquals(listOf(recording), mixed.sendable)
        assertEquals(1, mixed.missingVtt)
        assertEquals(1, mixed.missingMd)
    }

    @Test fun globalDocumentsChoiceCountsMissingBeforeTypeFilter() {
        val audio = recording.copy(name = "20261111120001", phoneFiles = mapOf(FileKind.MP3 to PhoneFile("Note-20261111120001.mp3", 4)))
        val local = LocalState(driveSendFormats = setOf(FileKind.VTT, FileKind.MD))
        val request = DriveSendRequest(null, setOf(FileKind.VTT, FileKind.MD))
        fun summary(list: List<Recording>) = DriveSync.dialogSummary(list, null, local, emptyMap(), request, request.formats)
        val only = summary(listOf(audio))
        assertTrue(only.sendable.isEmpty())
        assertEquals(1, only.missingVtt)
        assertEquals(1, only.missingMd)
        val mixed = summary(listOf(audio, recording))
        assertEquals(listOf(recording), mixed.sendable)
        assertEquals(1, mixed.missingVtt)
        assertEquals(1, mixed.missingMd)
        val explicit = DriveSync.dialogSummary(listOf(audio, recording), null, local, emptyMap(), request.copy(names = listOf(audio.name)), request.formats)
        assertTrue(explicit.sendable.isEmpty())
        assertEquals(1, explicit.missingVtt)
        assertEquals(1, explicit.missingMd)
        val newlyEligible = audio.copy(phoneFiles = audio.phoneFiles + (FileKind.VTT to PhoneFile("Note-${audio.name}.vtt", 4)))
        val confirmed = DriveSync.confirmedSendable(listOf(newlyEligible, recording), null, local, emptyMap(), request, request.formats, mixed.sendable.map { it.name }.toSet())
        assertEquals(listOf(recording), confirmed)
    }

    @Test fun exceptionDoesNotChangeNextRequestFormats() {
        val defaults = setOf(FileKind.VTT)
        val requests = DriveSendRequests()
        requests.open(listOf(name), defaults, customizeFormats = true)
        assertEquals(setOf(FileKind.MD), requests.confirm(setOf(FileKind.MD))?.formats)
        requests.open(listOf(name), defaults)
        assertEquals(defaults, requests.current?.formats)
    }

    @Test fun selectedFormatsControlCandidatesAndWarnings() {
        val local = LocalState(driveAccount = destination.account, chosenFolderId = destination.folderId, driveReceipts = receipts)
        val listing = DriveListing(remote.associate { it.name to it.size }, remote)
        val request = DriveSendRequest(null, setOf(FileKind.VTT))
        assertTrue(DriveSync.dialogSummary(listOf(recording), listing, local, hashes, request, request.formats).sendable.isEmpty())
        val mdChanged = hashes + (files.getValue(FileKind.MD).fileName to "changed")
        assertTrue(DriveSync.dialogSummary(listOf(recording), listing, local, mdChanged, request, request.formats).sendable.isEmpty())
        assertEquals(listOf(recording), DriveSync.dialogSummary(listOf(recording), listing, local, mdChanged, request, setOf(FileKind.MD)).sendable)
        assertTrue(DriveSync.dialogSummary(listOf(recording), listing, local, mdChanged, request, emptySet()).sendable.isEmpty())
    }

    @Test fun selectionClearsOnlyOnConfirmation() {
        val requests = DriveSendRequests()
        var clears = 0
        requests.open(listOf(name), FileKind.entries.toSet()) { clears++ }
        requests.cancel()
        assertEquals(0, clears)
        requests.open(listOf(name), FileKind.entries.toSet()) { clears++ }
        requests.confirm(setOf(FileKind.VTT, FileKind.MD))
        requests.confirm(FileKind.entries.toSet())
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
