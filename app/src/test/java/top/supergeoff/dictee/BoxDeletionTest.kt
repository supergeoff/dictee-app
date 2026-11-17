package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Test

class BoxDeletionTest {
    private val a = "20261117120000"
    private val b = "20261117120100"
    private fun recording(name: String, onBox: Boolean = true, mp3: Boolean = false) = Recording(
        name, RecordType.NOTE, 1, onBox,
        if (mp3) mapOf(FileKind.MP3 to PhoneFile("Note-$name.mp3", 1)) else emptyMap(), DisplayState.OnBox,
    )
    @Test fun selectionIsOrderedDistinctAndOnlyOnBox() {
        val rs = listOf(recording(b), recording(a, false), recording(b), recording(a))
        val p = BoxDeletion.plan("address", rs, setOf(a, b), setOf(b, "other"), null, LocalState())
        assertEquals("address", p.address)
        assertEquals(listOf(b, a), p.names)
        assertEquals(setOf(b), p.blockedNames)
        assertEquals(2, p.unbackedAudioCount)
    }
    @Test fun planDistinguishesWhichTargetLacksBackup() {
        val first = BoxDeletion.plan("address", listOf(recording(a, mp3 = true), recording(b)), setOf(a, b), emptySet(), null, LocalState())
        val changed = BoxDeletion.plan("address", listOf(recording(a), recording(b, mp3 = true)), setOf(a, b), emptySet(), null, LocalState())
        assertEquals(first.unbackedAudioCount, changed.unbackedAudioCount)
        assertNotEquals(first, changed)
    }
    @Test fun phoneMp3AndValidDriveListingCountAsCopies() {
        val rs = listOf(recording(a, mp3 = true), recording(b))
        val file = DriveFile("id", "$b.mp3", 1)
        val drive = DriveListing(mapOf(file.name to 1L), listOf(file))
        val local = LocalState(driveAccount = "account", chosenFolderId = "folder")
        fun count(d: DriveListing?, l: LocalState) = BoxDeletion.plan("address", rs, setOf(a, b), emptySet(), d, l).unbackedAudioCount
        assertEquals(0, count(drive, local))
        assertEquals(1, count(null, local))
        assertEquals(1, count(drive, LocalState()))
        assertEquals(1, count(DriveListing(drive.files, listOf(file, file.copy(id = "other"))), local))
    }
}
