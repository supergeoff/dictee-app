package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Test

class PhoneDeletionTest {
    private val a = "20261117120000"
    private val b = "20261117120100"
    private fun recording(name: String, kinds: Set<FileKind>, onBox: Boolean = false) = Recording(
        name, RecordType.NOTE, 1, onBox,
        kinds.associateWith { PhoneFile(RecordName.fileName(RecordType.NOTE, name, it), 1) }, DisplayState.Recovered,
    )
    private fun plan(rs: List<Recording>, selected: Set<String> = rs.map { it.name }.toSet(), protected: Set<String> = emptySet()) =
        PhoneDeletion.plan("folder-uri", rs, selected, protected)

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
    @Test fun executeDeletesExactMergedPhoneFilesAcrossTypeCollisions() {
        for ((type, flag, otherType) in listOf(
            Triple(RecordType.NOTE, 1, RecordType.CALL),
            Triple(RecordType.CALL, 0, RecordType.NOTE),
        )) {
            val selected = setOf("${type.label}-$a.mp3", "${otherType.label}-$a.md")
            val untouched = setOf("${otherType.label}-$a.mp3", "Note-$b.mp3", "Note-$a.md.part", "foreign.txt")
            val memory = MemoryStore()
            (selected + untouched).forEach { memory.files[it] = byteArrayOf(1) }
            val merged = Recordings.merge(
                listOf(ListItem.Entry(a, 1, flag)),
                (selected + untouched).map { PhoneFile(it, 1) }, null, emptyMap(), Busy(),
            )
            val recording = merged.single { it.name == a }
            assertEquals(type, recording.type)
            assertEquals(selected, recording.phoneFiles.values.map { it.fileName }.toSet())

            val result = PhoneDeletion.execute(plan(merged, selected = setOf(a)), memory)
            assertEquals(selected, result.deleted)
            assertTrue(result.failures.isEmpty())
            assertEquals(untouched, memory.files.keys)
        }
    }
}
