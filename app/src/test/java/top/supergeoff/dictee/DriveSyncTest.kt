package top.supergeoff.dictee

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveSyncTest {
    private val destination = DriveDestination("compte@example.test", "folder-1")
    private val fileName = "Call-20261030160502.vtt"

    private fun action(
        fileName: String = this.fileName,
        kind: FileKind = FileKind.VTT,
        sha256: String? = "abc",
        remote: List<DriveFile> = emptyList(),
        receipts: List<DriveReceipt> = emptyList(),
        destination: DriveDestination = this.destination,
    ) = DriveSync.action(fileName, kind, sha256, destination, remote, receipts)

    @Test fun `absent local documents require receipts bound to current destination and remote identity`() {
        val name = "20261030160502"
        val r = Recording(name, RecordType.NOTE, 0, false, emptyMap(), DisplayState.OnBox)
        val docs = listOf(DriveFile("v", "Call-$name.vtt", 1), DriveFile("m", "Call-$name.md", 1))
        val receipts = docs.map { DriveReceipt(destination, it.name, it.id, "hash") }
        fun complete(remote: List<DriveFile> = docs, proof: List<DriveReceipt> = receipts, choice: DriveSendChoice = DriveSendChoice.DOCUMENTS_ONLY, dest: DriveDestination = destination) =
            DriveSync.isComplete(r, choice, dest, remote, emptyMap(), proof)
        assertEquals(true, complete())
        assertEquals(false, complete(proof = emptyList()))
        assertEquals(false, complete(dest = DriveDestination("other", "folder")))
        assertEquals(false, complete(remote = docs.map { it.copy(id = "different") }))
        assertEquals(false, complete(remote = docs + docs.first().copy(id = "duplicate")))
        assertEquals(false, complete(choice = DriveSendChoice.ALL_FILES))
        assertEquals(true, complete(remote = docs + DriveFile("audio", "$name.mp3", 1), choice = DriveSendChoice.ALL_FILES))
        val local = r.copy(phoneFiles = mapOf(FileKind.VTT to PhoneFile(docs.first().name, 1)))
        assertEquals(false, DriveSync.isComplete(local, DriveSendChoice.DOCUMENTS_ONLY, destination, docs, emptyMap(), receipts))
    }

    @Test fun `documents only selects VTT and MD`() {
        assertEquals(setOf(FileKind.VTT, FileKind.MD), DriveSync.requiredKinds(DriveSendChoice.DOCUMENTS_ONLY))
    }

    @Test fun `absent document is created`() {
        assertEquals(DriveWriteAction.CREATE, action())
    }

    @Test fun `same name without receipt is updated`() {
        assertEquals(DriveWriteAction.UPDATE, action(remote = listOf(DriveFile("remote-1", fileName, 3))))
    }

    @Test fun `matching receipt and visible id skip upload`() {
        val remote = listOf(DriveFile("remote-1", fileName, 3))
        val receipt = DriveReceipt(destination, fileName, "remote-1", "abc")
        assertEquals(DriveWriteAction.SKIP, action(remote = remote, receipts = listOf(receipt)))
    }

    @Test fun `missing hash never proves file was saved`() {
        val remote = listOf(DriveFile("remote-1", fileName, 3))
        val receipt = DriveReceipt(destination, fileName, "remote-1", "abc")
        assertEquals(DriveWriteAction.UPDATE, action(sha256 = null, remote = remote, receipts = listOf(receipt)))
    }

    @Test fun `changed content updates even at same size`() {
        val remote = listOf(DriveFile("remote-1", fileName, 3))
        val receipt = DriveReceipt(destination, fileName, "remote-1", "abc")
        assertEquals(DriveWriteAction.UPDATE, action(sha256 = "abd", remote = remote, receipts = listOf(receipt)))
    }

    @Test fun `receipt for other destination does not skip`() {
        val remote = listOf(DriveFile("remote-1", fileName, 3))
        val receipt = DriveReceipt(DriveDestination("other@example.test", "folder-2"), fileName, "remote-1", "abc")
        assertEquals(DriveWriteAction.UPDATE, action(remote = remote, receipts = listOf(receipt)))
    }

    @Test fun `deleted remote id causes create`() {
        val receipt = DriveReceipt(destination, fileName, "deleted", "abc")
        assertEquals(DriveWriteAction.CREATE, action(receipts = listOf(receipt)))
    }

    @Test fun `multiple files with same name conflict`() {
        val remote = listOf(DriveFile("one", fileName, 3), DriveFile("two", fileName, 3))
        assertEquals(DriveWriteAction.CONFLICT, action(remote = remote))
    }

    @Test fun `legacy MP3 name remains present and is skipped`() {
        val name = "Call-20261030160502.mp3"
        val remote = listOf(DriveFile("legacy", "20261030160502.mp3", 3))
        assertEquals(DriveWriteAction.SKIP, action(fileName = name, kind = FileKind.MP3, remote = remote))
    }

    @Test fun `other prefix MP3 is skipped and two prefixes conflict`() {
        val name = "Note-20261030160502.mp3"
        val call = DriveFile("call", "Call-20261030160502.mp3", 3)
        val note = DriveFile("note", name, 3)
        assertEquals(DriveWriteAction.SKIP, action(fileName = name, kind = FileKind.MP3, remote = listOf(call)))
        assertEquals(DriveWriteAction.CONFLICT, action(fileName = name, kind = FileKind.MP3, remote = listOf(note, call)))
        assertEquals(DriveWriteAction.CONFLICT, action(fileName = name, kind = FileKind.MP3, remote = listOf(call, DriveFile("legacy", "20261030160502.mp3", 3))))
    }

    @Test fun `current MP3 name without receipt is never replaced`() {
        val name = "Call-20261030160502.mp3"
        val remote = listOf(DriveFile("current", name, 3))
        assertEquals(DriveWriteAction.SKIP, action(fileName = name, kind = FileKind.MP3, remote = remote))
    }

    @Test fun `current MP3 with different hash is never replaced`() {
        val name = "Call-20261030160502.mp3"
        val remote = listOf(DriveFile("current", name, 3))
        val receipt = DriveReceipt(destination, name, "current", "old-hash")
        assertEquals(
            DriveWriteAction.SKIP,
            action(fileName = name, kind = FileKind.MP3, sha256 = "new-hash", remote = remote, receipts = listOf(receipt)),
        )
    }
}
