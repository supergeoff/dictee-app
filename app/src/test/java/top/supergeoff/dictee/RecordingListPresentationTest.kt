package top.supergeoff.dictee

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingListPresentationTest {
    private val name = "20261111120000"
    private fun recording(vararg kinds: FileKind) = Recording(name, RecordType.NOTE, 100, true,
        kinds.associateWith { PhoneFile(RecordName.fileName(RecordType.NOTE, name, it), 100) }, DisplayState.OnBox)

    @Test fun listBadgeUsesPipeline() {
        val cases = listOf(
            recording(FileKind.MP3) to "MP3",
            recording(FileKind.MP3, FileKind.VTT) to "VTT",
            recording(FileKind.MP3, FileKind.VTT, FileKind.MD) to "MD",
        )
        cases.forEach { (r, expected) -> assertEquals(expected, recordingListPresentation(r, UiState()).badge) }
        val all = recording(FileKind.MP3, FileKind.VTT, FileKind.MD)
        val drive = DriveListing(all.phoneFiles.values.associate { it.fileName to it.size })
        assertEquals("Sur Drive", recordingListPresentation(all, UiState(local = LocalState(driveEnabled = true), drive = drive)).badge)
    }
}
