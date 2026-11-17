package top.supergeoff.dictee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneDeletionPresentationTest {
    private fun recording(name: String, hasFile: Boolean) = Recording(name, RecordType.NOTE, 1, false,
        if (hasFile) mapOf(FileKind.MP3 to PhoneFile("Note-$name.mp3", 1)) else emptyMap(), DisplayState.Recovered)

    @Test fun `fiche retrouve sa ligne ou revient si elle disparait`() {
        val first = recording("first", true)
        val second = recording("second", false)
        assertEquals(first, recordingForDetail(listOf(first, second), "first"))
        assertNull(recordingForDetail(listOf(second), "first"))
    }
}
