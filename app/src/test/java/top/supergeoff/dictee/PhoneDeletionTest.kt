package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Test

class PhoneDeletionTest {
    private val a = "20261117120000"
    private val b = "20261117120100"
    private val dest = DriveDestination("account", "folder")
    private fun recording(name: String, kinds: Set<FileKind>, onBox: Boolean = false) = Recording(
        name, RecordType.NOTE, 1, onBox,
        kinds.associateWith { PhoneFile(RecordName.fileName(RecordType.NOTE, name, it), 1) }, DisplayState.Recovered,
    )
    private fun plan(rs: List<Recording>, selected: Set<String> = rs.map { it.name }.toSet(), protected: Set<String> = emptySet(),
        drive: DriveListing? = null, local: LocalState = LocalState(), hashes: Map<String, String> = emptyMap()) =
        PhoneDeletion.plan("folder-uri", rs, selected, protected, drive, local, hashes)

    @Test fun selectionDeduplicatesAndOmitsRecordingsWithoutFiles() {
        val one = recording(a, setOf(FileKind.MP3)); val two = recording(b, setOf(FileKind.MD))
        val p = plan(listOf(one, one, two, recording("20261117120200", emptySet())))
        assertEquals(listOf(one, two), p.recordings)
        assertEquals("folder-uri", p.folderUri)
    }
    @Test fun protectedTargetBlocksEntireExecution() {
        val p = plan(listOf(recording(a, setOf(FileKind.MP3)), recording(b, setOf(FileKind.MD))), protected = setOf(b))
        assertEquals(setOf(b), p.blockedNames)
        val calls = mutableListOf<String>()
        val store = object : PhoneStore by MemoryStore() { override fun delete(fileName: String) { calls += fileName } }
        assertTrue(PhoneDeletion.execute(p, store).deleted.isEmpty())
        assertTrue(calls.isEmpty())
    }
    @Test fun unknownDriveAndLocalOnlyMp3Warn() {
        val p = plan(listOf(recording(a, setOf(FileKind.MP3, FileKind.VTT))))
        assertTrue(p.documentsUnverified); assertTrue(p.audioAtRisk)
    }
    @Test fun boxCopyPreventsAudioWarning() {
        assertFalse(plan(listOf(recording(a, setOf(FileKind.MP3), true))).audioAtRisk)
    }
    @Test fun receiptsMustMatchHashAndDestination() {
        val r = recording(a, setOf(FileKind.VTT, FileKind.MD))
        val remote = r.phoneFiles.values.map { DriveFile(it.fileName, it.fileName, 1) }
        val listing = DriveListing(remote.associate { it.name to it.size }, remote)
        val hashes = remote.associate { it.name to "current" }
        val receipts = remote.map { DriveReceipt(dest, it.name, it.id, "current") }
        val local = LocalState(driveAccount = dest.account, chosenFolderId = dest.folderId, driveReceipts = receipts)
        assertFalse(plan(listOf(r), drive = listing, local = local, hashes = hashes).documentsUnverified)
        assertTrue(plan(listOf(r), drive = listing, local = local, hashes = hashes.mapValues { "new" }).documentsUnverified)
        assertTrue(plan(listOf(r), drive = listing, local = local.copy(chosenFolderId = "other"), hashes = hashes).documentsUnverified)
    }
    @Test fun executeContinuesAfterRefusalAndOnlyDeletesPlannedFiles() {
        val r = recording(a, setOf(FileKind.MP3, FileKind.VTT))
        val names = r.phoneFiles.values.map { it.fileName }
        val memory = MemoryStore(); memory.files[names[0]] = byteArrayOf(1); memory.files[names[1]] = byteArrayOf(2)
        val calls = mutableListOf<String>()
        val store = object : PhoneStore by memory {
            override fun delete(fileName: String) { calls += fileName; if (fileName == names[1]) throw PhoneStoreException("refus"); memory.delete(fileName) }
        }
        val result = PhoneDeletion.execute(plan(listOf(r)), store)
        assertEquals(setOf(names[0]), result.deleted); assertEquals(mapOf(names[1] to "refus"), result.failures)
        assertEquals(names.toSet(), calls.toSet())
    }
    @Test fun absentFileIsIdempotent() { MemoryStore().delete("absent") }
    @Test fun cancellationEscapesExecution() {
        val store = object : PhoneStore by MemoryStore() {
            override fun delete(fileName: String) { throw java.util.concurrent.CancellationException("cancelled") }
        }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            PhoneDeletion.execute(plan(listOf(recording(a, setOf(FileKind.MP3)))), store)
        }
    }
    @Test fun executionIgnoresUnrecognizedAndForeignNames() {
        val r = recording(a, setOf(FileKind.MP3)).copy(phoneFiles = mapOf(
            FileKind.MP3 to PhoneFile("Note-$b.mp3", 1), FileKind.MD to PhoneFile("Note-$a.md.part", 1),
        ))
        val calls = mutableListOf<String>()
        val store = object : PhoneStore by MemoryStore() { override fun delete(fileName: String) { calls += fileName } }
        PhoneDeletion.execute(plan(listOf(r)), store)
        assertTrue(calls.isEmpty())
    }
    @Test fun driveMp3PreventsAudioWarning() {
        val r = recording(a, setOf(FileKind.MP3))
        val name = r.phoneFiles.getValue(FileKind.MP3).fileName
        val drive = DriveListing(mapOf(name to 1L), listOf(DriveFile("id", name, 1)))
        val local = LocalState(driveAccount = dest.account, chosenFolderId = dest.folderId)
        assertFalse(plan(listOf(r), drive = drive, local = local).audioAtRisk)
    }
}
