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

    private val configured = LocalState(driveAccount = "account", chosenFolderId = "folder", driveEnabled = true, driveSendFormats = setOf(FileKind.VTT, FileKind.MD))
    private val destination = DriveDestination("account", "folder")
    private fun hashes(r: Recording, sha: String = "sha") = r.phoneFiles.filterKeys { it != FileKind.MP3 }.values.associate { it.fileName to sha }
    private fun receipts(vararg files: DriveFile, sha: String = "sha") = files.map { DriveReceipt(destination, it.name, it.id, sha) }

    @Test fun cloudCountIgnoresAudio() {
        val r = recording(kinds = setOf(FileKind.MP3))
        assertEquals(0, cloudSendCount(listOf(r), listing(), configured, emptyMap()))
    }

    @Test fun cloudCountCountsMissingDocuments() {
        val r = recording(kinds = setOf(FileKind.MP3, FileKind.VTT, FileKind.MD))
        val vtt = driveFile(FileKind.VTT)
        val md = driveFile(FileKind.MD)
        val local = configured.copy(driveReceipts = receipts(vtt, md))
        assertEquals(1, cloudSendCount(listOf(r), listing(vtt), local, hashes(r)))
        assertEquals(0, cloudSendCount(listOf(r), listing(vtt, md), local, hashes(r)))
    }

    @Test fun cloudCountCountsDocumentsModifiedSinceSend() {
        val r = recording(kinds = setOf(FileKind.VTT, FileKind.MD))
        val vtt = driveFile(FileKind.VTT)
        val md = driveFile(FileKind.MD)
        val local = configured.copy(driveReceipts = receipts(vtt, md))
        assertEquals(1, cloudSendCount(listOf(r), listing(vtt, md), local, hashes(r, sha = "modifié")))
    }

    @Test fun cloudCountIgnoresRecordingWithRecoverError() {
        val r = recording(kinds = setOf(FileKind.VTT))
        val local = configured.copy(errors = mapOf(a to mapOf(Step.RECOVER to "interrompue")))
        assertEquals(0, cloudSendCount(listOf(r), listing(), local, hashes(r)))
    }

    @Test fun cloudCountZeroWhenDriveDisabled() {
        val r = recording(kinds = setOf(FileKind.VTT))
        assertEquals(0, cloudSendCount(listOf(r), listing(), configured.copy(driveEnabled = false), hashes(r)))
    }

    @Test fun cloudCountUsesFormatsWithoutListing() {
        val r = recording(kinds = setOf(FileKind.VTT))
        assertEquals(1, cloudSendCount(listOf(r), null, configured, hashes(r)))
    }
}
