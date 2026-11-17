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

    @Test fun `selection conserve echecs partiels et autres lignes presentes`() {
        val remaining = selectionAfterPhoneDeletion(
            setOf("deleted", "partial", "other", "gone"), setOf("deleted", "partial", "gone"),
            listOf(recording("deleted", false), recording("partial", true), recording("other", false)))
        assertEquals(setOf("partial", "other"), remaining)
        assertEquals(emptySet<String>(), selectionAfterPhoneDeletion(setOf("deleted"), setOf("deleted"), listOf(recording("deleted", false))))
    }
}
