package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Test

class RecordingLocationTest {
    private val a = "20261117120000"

    private fun recording(onBox: Boolean = false, kinds: Set<FileKind> = emptySet()) = Recording(
        a, RecordType.NOTE, 1, onBox,
        kinds.sorted().associateWith { PhoneFile(RecordName.fileName(RecordType.NOTE, a, it), 1) }, DisplayState.OnBox,
    )

    private fun driveFile(kind: FileKind) = DriveFile("id-$kind", RecordName.fileName(RecordType.NOTE, a, kind), 1)
    private fun listing(vararg files: DriveFile) = DriveListing(files.associate { it.name to it.size }, files.toList())

    @Test fun filterOrderAndLabels() {
        assertEquals(listOf("Tout", "Boîtier", "Téléphone", "Drive"), LocationFilter.entries.map { it.label })
    }

    @Test fun recordingInSeveralFilters() {
        val r = recording(onBox = true, kinds = setOf(FileKind.MP3))
        val drive = listing(driveFile(FileKind.MP3))
        assertEquals(LocationFilter.entries.toList(), LocationFilter.entries.filter { it.matches(r, drive) })
    }

    @Test fun recordingOnPhoneOnlyMatchesAllAndPhone() {
        val r = recording(kinds = setOf(FileKind.MP3))
        assertEquals(listOf(LocationFilter.ALL, LocationFilter.PHONE), LocationFilter.entries.filter { it.matches(r, null) })
    }

    @Test fun driveFilterZeroWithoutListing() {
        assertFalse(LocationFilter.DRIVE.matches(recording(onBox = true, kinds = setOf(FileKind.MP3)), null))
    }

    @Test fun cloudCountIgnoresAudio() {
        val r = recording(kinds = setOf(FileKind.MP3))
        assertEquals(0, cloudSendCount(listOf(r), listing(), driveEnabled = true))
    }

    @Test fun cloudCountCountsMissingDocuments() {
        val r = recording(kinds = setOf(FileKind.MP3, FileKind.VTT, FileKind.MD))
        val complete = listing(driveFile(FileKind.VTT), driveFile(FileKind.MD))
        assertEquals(1, cloudSendCount(listOf(r), listing(driveFile(FileKind.VTT)), driveEnabled = true))
        assertEquals(0, cloudSendCount(listOf(r), complete, driveEnabled = true))
    }

    @Test fun cloudCountZeroWhenDriveDisabled() {
        val r = recording(kinds = setOf(FileKind.VTT))
        assertEquals(0, cloudSendCount(listOf(r), listing(), driveEnabled = false))
    }

    @Test fun cloudCountZeroWithoutListing() {
        val r = recording(kinds = setOf(FileKind.VTT))
        assertEquals(0, cloudSendCount(listOf(r), null, driveEnabled = true))
    }
}
