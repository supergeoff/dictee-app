package top.supergeoff.dictee

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingListPresentationTest {
    private val name = "20261111120000"
    private fun recording(vararg kinds: FileKind) = Recording(name, RecordType.NOTE, 100, true,
        kinds.associateWith { PhoneFile(RecordName.fileName(RecordType.NOTE, name, it), 100) }, DisplayState.OnBox)

    @Test fun sharedBadgeReflectsMigrationDocumentsOnlyAndChangedHash() {
        val all = recording(FileKind.MP3, FileKind.VTT, FileKind.MD).copy(state = DisplayState.OnDrive)
        val destination = DriveDestination("account", "folder")
        val entries = all.phoneFiles.values.map { DriveFile(it.fileName, it.fileName, it.size) }
        val hashes = entries.filterNot { it.name.endsWith(".mp3") }.associate { it.name to "hash" }
        val receipts = entries.filter { it.name in hashes }.map { DriveReceipt(destination, it.name, it.id, "hash") }
        val local = LocalState(driveEnabled = true, driveAccount = "account", chosenFolderId = "folder", driveReceipts = receipts)
        fun listing(files: List<DriveFile>) = DriveListing(files.associate { it.name to it.size }, files)
        val ui = UiState(local = local, drive = listing(entries), documentHashes = hashes)
        assertEquals("MD", recordingListPresentation(all, ui.copy(local = local.copy(driveReceipts = emptyList()))).badge)
        val docsOnly = ui.copy(local = local.copy(driveSendChoice = DriveSendChoice.DOCUMENTS_ONLY), drive = listing(entries.filterNot { it.name.endsWith(".mp3") }))
        assertEquals("Sur Drive", recordingListPresentation(all, docsOnly).badge)
        val changed = hashes + (all.phoneFiles.getValue(FileKind.VTT).fileName to "changed")
        assertEquals("MD", recordingListPresentation(all, ui.copy(documentHashes = changed)).badge)
    }

    @Test fun staleProvenanceMatchesDetailPipeline() {
        val all = recording(FileKind.MP3, FileKind.VTT, FileKind.MD)
        val ui = UiState()
        val detail = RecordingPipeline.calculate(PipelineInput(all, ui.local, ui.busy, ui.drive, false, false, ProvenanceStatus.STALE))
        assertEquals(detail.badge, recordingListPresentation(all, ui, ProvenanceStatus.STALE).badge)
    }

    @Test fun listBadgeUsesPipeline() {
        val cases = listOf(
            recording(FileKind.MP3) to "MP3",
            recording(FileKind.MP3, FileKind.VTT) to "VTT",
            recording(FileKind.MP3, FileKind.VTT, FileKind.MD) to "MD",
        )
        cases.forEach { (r, expected) -> assertEquals(expected, recordingListPresentation(r, UiState()).badge) }
        val all = recording(FileKind.MP3, FileKind.VTT, FileKind.MD)
        val drive = DriveListing(all.phoneFiles.values.associate { it.fileName to it.size })
        assertEquals("MD", recordingListPresentation(all, UiState(local = LocalState(driveEnabled = true), drive = drive)).badge)
    }
}
