package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryStoreGuardTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun readAndGuardErrorsAreWrappedLikePhoneFolder() {
        val store = MemoryStore()
        val missing = tmp.root.resolve("missing")
        val readError = assertThrows(PhoneStoreException::class.java) { store.writeGuarded("note.md", missing, true) { it() } }
        assertTrue(readError.cause is java.io.FileNotFoundException)
        val source = tmp.newFile().apply { writeText("new") }
        val original = IllegalStateException("guard error")
        val guardError = assertThrows(PhoneStoreException::class.java) { store.writeGuarded("note.md", source, true) { throw original } }
        assertSame(original, guardError.cause)
        val phoneError = PhoneStoreException("refused")
        assertSame(phoneError, assertThrows(PhoneStoreException::class.java) { store.writeGuarded("note.md", source, true) { throw phoneError } })
    }

    @Test fun caughtPromotionFailureIsStillReportedAndCannotBeRetried() {
        val store = MemoryStore()
        val source = tmp.newFile().apply { writeText("new") }
        store.files["note.md"] = "old".toByteArray()
        assertThrows(PhoneStoreException::class.java) {
            store.writeGuarded("note.md", source, false) { promote ->
                assertThrows(PhoneStoreException::class.java) { promote() }
            }
        }
        assertEquals("old", store.files.getValue("note.md").decodeToString())
        assertThrows(PhoneStoreException::class.java) {
            store.writeGuarded("note.md", source, false) { promote ->
                assertThrows(PhoneStoreException::class.java) { promote() }
                promote()
            }
        }
        assertEquals("old", store.files.getValue("note.md").decodeToString())
    }

    @Test fun successfulPromotionThenGuardErrorStillWritesAndReportsError() {
        val store = MemoryStore()
        val source = tmp.newFile().apply { writeText("new") }
        val original = IllegalStateException("after promotion")
        val error = assertThrows(PhoneStoreException::class.java) {
            store.writeGuarded("note.md", source, false) { promote -> promote(); throw original }
        }
        assertSame(original, error.cause)
        assertEquals("new", store.files.getValue("note.md").decodeToString())
    }

    @Test fun promotionCannotRunTwiceOrAfterGuardReturns() {
        val store = MemoryStore()
        val source = tmp.newFile().apply { writeText("new") }
        val error = assertThrows(PhoneStoreException::class.java) {
            store.writeGuarded("note.md", source, true) { promote -> promote(); promote() }
        }
        assertTrue(error.cause is IllegalStateException)
        assertEquals("new", store.files.getValue("note.md").decodeToString())
        lateinit var delayed: () -> Unit
        assertThrows(PhoneStoreException::class.java) { store.writeGuarded("other.md", source, true) { delayed = it } }
        assertThrows(IllegalStateException::class.java) { delayed() }
        assertFalse(store.files.containsKey("other.md"))
    }

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
