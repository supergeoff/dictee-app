package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Test

class UnifiedDeletionTest {
    private val a = "20261117120000"
    private val b = "20261117120100"
    private val destination = DriveDestination("account", "folder")
    private val configured = LocalState(driveAccount = "account", chosenFolderId = "folder", driveEnabled = true)

    private fun recording(name: String, onBox: Boolean = false, kinds: Set<FileKind> = emptySet()) = Recording(
        name, RecordType.NOTE, 1, onBox,
        kinds.sorted().associateWith { PhoneFile(RecordName.fileName(RecordType.NOTE, name, it), 1) }, DisplayState.OnBox,
    )

    private fun driveFile(name: String, kind: FileKind) = DriveFile("id-$kind-$name", RecordName.fileName(RecordType.NOTE, name, kind), 1)
    private fun listing(vararg files: DriveFile) = DriveListing(files.associate { it.name to it.size }, files.toList())
    private fun receipts(vararg files: DriveFile) = files.map { DriveReceipt(destination, it.name, it.id, "sha") }

    private fun plan(
        mode: DeletionMode, recordings: List<Recording>, drive: DriveListing? = null, local: LocalState = configured,
        selected: Set<String> = recordings.map { it.name }.toSet(), protectedNames: Set<String> = emptySet(),
    ) = UnifiedDeletion.plan(mode, recordings, selected, protectedNames, drive, local, "address", "folder-uri")

    @Test fun everywhereTargetsAllLevels() {
        val files = listOf(driveFile(a, FileKind.MP3), driveFile(a, FileKind.VTT), driveFile(a, FileKind.MD))
        val p = plan(DeletionMode.EVERYWHERE, listOf(recording(a, true, FileKind.entries.toSet())), listing(*files.toTypedArray()))
        assertEquals(listOf(DeletionTarget(a, true, true, files)), p.targets)
        assertFalse(p.driveSkipped)
        assertEquals("address", p.boxAddress)
        assertEquals("folder-uri", p.folderUri)
        assertEquals(DeletionMode.EVERYWHERE, p.mode)
    }

    @Test fun everywhereBoxOnly() {
        val p = plan(DeletionMode.EVERYWHERE, listOf(recording(a, true)))
        assertEquals(listOf(DeletionTarget(a, true, false, emptyList())), p.targets)
    }

    @Test fun freeSpaceBoxAndPhoneKeepsPhone() {
        val p = plan(DeletionMode.FREE_SPACE, listOf(recording(a, true, setOf(FileKind.MP3))))
        assertEquals(listOf(DeletionTarget(a, true, false, emptyList())), p.targets)
    }

    @Test fun freeSpaceWithConfirmedDriveFreesBoxAndPhone() {
        val files = listOf(driveFile(a, FileKind.VTT), driveFile(a, FileKind.MD))
        val local = configured.copy(driveReceipts = receipts(*files.toTypedArray()))
        val p = plan(DeletionMode.FREE_SPACE, listOf(recording(a, true, FileKind.entries.toSet())), listing(*files.toTypedArray()), local)
        assertEquals(listOf(DeletionTarget(a, true, true, emptyList())), p.targets)
    }

    @Test fun freeSpaceKeepsPhoneWithoutConfirmedDocuments() {
        val vtt = driveFile(a, FileKind.VTT)
        val md = driveFile(a, FileKind.MD)
        val rs = listOf(recording(a, true, FileKind.entries.toSet()))
        val onlyVtt = configured.copy(driveReceipts = receipts(vtt))
        assertEquals(
            listOf(DeletionTarget(a, true, false, emptyList())),
            plan(DeletionMode.FREE_SPACE, rs, listing(vtt), onlyVtt).targets,
        )
        val noReceiptForMd = configured.copy(driveReceipts = receipts(vtt))
        assertEquals(
            listOf(DeletionTarget(a, true, false, emptyList())),
            plan(DeletionMode.FREE_SPACE, rs, listing(vtt, md), noReceiptForMd).targets,
        )
    }

    @Test fun freeSpaceWithoutDriveDestinationKeepsPhone() {
        val files = listOf(driveFile(a, FileKind.VTT), driveFile(a, FileKind.MD))
        val local = LocalState(driveEnabled = true, driveReceipts = receipts(*files.toTypedArray()))
        val p = plan(DeletionMode.FREE_SPACE, listOf(recording(a, true, FileKind.entries.toSet())), listing(*files.toTypedArray()), local)
        assertEquals(listOf(DeletionTarget(a, true, false, emptyList())), p.targets)
    }

    @Test fun freeSpaceBoxOnlyHasNoTarget() {
        val p = plan(DeletionMode.FREE_SPACE, listOf(recording(a, true)))
        assertTrue(p.targets.isEmpty())
    }

    @Test fun freeSpaceNeverTouchesDrive() {
        val files = listOf(driveFile(a, FileKind.MP3), driveFile(a, FileKind.VTT), driveFile(a, FileKind.MD))
        val local = configured.copy(driveReceipts = receipts(*files.toTypedArray()))
        val p = plan(DeletionMode.FREE_SPACE, listOf(recording(a, true, FileKind.entries.toSet())), listing(*files.toTypedArray()), local)
        assertTrue(p.targets.all { it.driveFiles.isEmpty() })
        assertFalse(p.driveSkipped)
    }

    @Test fun driveDisabledSkipsDrive() {
        val file = driveFile(a, FileKind.MP3)
        val p = plan(DeletionMode.EVERYWHERE, listOf(recording(a, true)), listing(file), configured.copy(driveEnabled = false))
        assertEquals(listOf(DeletionTarget(a, true, false, emptyList())), p.targets)
        assertTrue(p.driveSkipped)
    }

    @Test fun driveDisabledWithoutDriveFileIsNotSkipped() {
        val p = plan(DeletionMode.EVERYWHERE, listOf(recording(a, true)), null, configured.copy(driveEnabled = false))
        assertFalse(p.driveSkipped)
    }

    @Test fun blocked() {
        val rs = listOf(recording(a, true), recording(b, true))
        val p = plan(DeletionMode.EVERYWHERE, rs, protectedNames = setOf(b, "other"))
        assertEquals(setOf(b), p.blockedNames)
    }

    @Test fun legacyMp3OnDriveIsTargeted() {
        val legacy = DriveFile("legacy-id", "$a.mp3", 1)
        val p = plan(DeletionMode.EVERYWHERE, listOf(recording(a)), listing(legacy))
        assertEquals(listOf(DeletionTarget(a, false, false, listOf(legacy))), p.targets)
    }

    @Test fun onlySelectedRecordingsAreTargetedAndEmptyTargetsDropped() {
        val rs = listOf(recording(a, true), recording(b, true), recording("20261117120200"))
        val p = plan(DeletionMode.EVERYWHERE, rs, selected = setOf(b, "20261117120200"))
        assertEquals(listOf(DeletionTarget(b, true, false, emptyList())), p.targets)
    }

    @Test fun otherRecordingsDriveFilesAreNotTargeted() {
        val other = driveFile(b, FileKind.MP3)
        val p = plan(DeletionMode.EVERYWHERE, listOf(recording(a, true)), listing(other))
        assertEquals(listOf(DeletionTarget(a, true, false, emptyList())), p.targets)
        assertEquals(emptyList<DriveFile>(), driveFilesOf(a, listing(other)))
        assertEquals(emptyList<DriveFile>(), driveFilesOf(a, null))
    }
}
