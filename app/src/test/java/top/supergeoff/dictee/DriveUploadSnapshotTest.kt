package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DriveUploadSnapshotTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun snapshotKeepsHashedBytesAndDeletesOnlyCopy() {
        val store = MemoryStore().apply { files["note.vtt"] = "abc".toByteArray() }
        val snapshot = DriveUploadSnapshot.capture(store, "note.vtt", tmp.root)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", snapshot.sha256)
        assertEquals(3L, snapshot.size)
        store.files["note.vtt"] = "abd".toByteArray()
        assertEquals("abc", snapshot.open().bufferedReader().use { it.readText() })
        assertEquals(1, tmp.root.listFiles()!!.size)
        snapshot.close()
        assertEquals(0, tmp.root.listFiles()!!.size)
        assertEquals("abd", store.files.getValue("note.vtt").decodeToString())
    }
}
