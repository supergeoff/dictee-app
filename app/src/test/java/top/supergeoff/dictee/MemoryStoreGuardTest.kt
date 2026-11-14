package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryStoreGuardTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun guardRefusalAndMissingModeDoNotReplaceExistingFile() {
        val store = MemoryStore()
        val source = tmp.newFile().apply { writeText("new") }
        store.files["note.md"] = "old".toByteArray()
        var calls = 0
        assertThrows(PhoneStoreException::class.java) { store.writeGuarded("note.md", source, true) { calls++ } }
        assertEquals(1, calls)
        assertEquals("old", store.files.getValue("note.md").decodeToString())
        assertThrows(PhoneStoreException::class.java) { store.writeGuarded("note.md", source, false) { it() } }
        assertEquals("old", store.files.getValue("note.md").decodeToString())
        store.writeGuarded("note.md", source, true) { it() }
        assertEquals("new", store.files.getValue("note.md").decodeToString())
    }
}
