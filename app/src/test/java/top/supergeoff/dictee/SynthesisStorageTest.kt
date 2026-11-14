package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class SynthesisStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun stageSurvivesReopeningWithExactBytesAndHash() {
        val dir = tmp.newFolder()
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val hash = SynthesisStorage(dir).stage("task-1", bytes)
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expected, hash)
        assertArrayEquals(bytes, SynthesisStorage(dir).staged("task-1", hash).readBytes())
    }

    @Test fun stagedRejectsMissingCorruptedAndWrongHash() {
        val storage = SynthesisStorage(tmp.newFolder())
        assertThrows(Exception::class.java) { storage.staged("missing", "a".repeat(64)) }
        val hash = storage.stage("task-1", "synthetic".toByteArray())
        assertThrows(Exception::class.java) { storage.staged("task-1", "b".repeat(64)) }
        storage.staged("task-1", hash).writeText("corrupted")
        assertThrows(Exception::class.java) { storage.staged("task-1", hash) }
    }

    @Test fun unsafeIdsAreRejectedAndDiscardIsIsolated() {
        val storage = SynthesisStorage(tmp.newFolder())
        for (id in listOf("", ".", "..", "../other", "a/b", "a\\b")) {
            assertThrows(Exception::class.java) { storage.stage(id, byteArrayOf(1)) }
            assertThrows(Exception::class.java) { storage.staged(id, "a".repeat(64)) }
            assertThrows(Exception::class.java) { storage.discard(id) }
        }
        storage.stage("task-1", byteArrayOf(1))
        val hash = storage.stage("task-2", byteArrayOf(2))
        storage.discard("task-1")
        assertThrows(Exception::class.java) { storage.staged("task-1", hash) }
        assertArrayEquals(byteArrayOf(2), storage.staged("task-2", hash).readBytes())
    }

    @Test fun provenanceRequiresFolderAndBothHashes() {
        val p = SynthesisProvenance("content://synthetic/tree", "note.md", "model", "a".repeat(64), "b".repeat(64))
        assertEquals(ProvenanceStatus.CURRENT, provenanceStatus(p, p.folderUri, p.vttSha256, p.mdSha256))
        assertEquals(ProvenanceStatus.STALE, provenanceStatus(p, p.folderUri, "c".repeat(64), p.mdSha256))
        assertEquals(ProvenanceStatus.UNKNOWN, provenanceStatus(null, p.folderUri, p.vttSha256, p.mdSha256))
        assertEquals(ProvenanceStatus.UNKNOWN, provenanceStatus(p, "content://other/tree", p.vttSha256, p.mdSha256))
        assertEquals(ProvenanceStatus.UNKNOWN, provenanceStatus(p, p.folderUri, p.vttSha256, "c".repeat(64)))
        assertEquals(ProvenanceStatus.UNKNOWN, provenanceStatus(p, p.folderUri, null, p.mdSha256))
        assertEquals(ProvenanceStatus.UNKNOWN, provenanceStatus(p, p.folderUri, p.vttSha256, null))
        assertEquals(ProvenanceStatus.UNKNOWN, provenanceStatus(p.copy(vttSha256 = ""), p.folderUri, "", p.mdSha256))
        assertEquals(ProvenanceStatus.UNKNOWN, provenanceStatus(p.copy(mdSha256 = ""), p.folderUri, p.vttSha256, ""))
    }
}
