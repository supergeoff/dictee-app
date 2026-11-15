package top.supergeoff.dictee

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingListPresentationTest {
    private val name = "20261111120000"
    private fun recording(vararg kinds: FileKind) = Recording(name, RecordType.NOTE, 100, true,
        kinds.associateWith { PhoneFile(RecordName.fileName(RecordType.NOTE, name, it), 100) }, DisplayState.OnBox)

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
