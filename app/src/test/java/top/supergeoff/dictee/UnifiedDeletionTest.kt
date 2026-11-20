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

    /** Empreintes actuelles des documents du téléphone : par défaut « sha », celle des reçus de [receipts]. */
    private fun currentHashes(recordings: List<Recording>, sha: String = "sha") = recordings
        .flatMap { it.phoneFiles.filterKeys { kind -> kind != FileKind.MP3 }.values }.associate { it.fileName to sha }

    private fun plan(
        mode: DeletionMode, recordings: List<Recording>, drive: DriveListing? = null, local: LocalState = configured,
        selected: Set<String> = recordings.map { it.name }.toSet(), protectedNames: Set<String> = emptySet(),
        hashes: Map<String, String> = currentHashes(recordings), phoneReadable: Boolean = true,
    ) = UnifiedDeletion.plan(mode, recordings, selected, protectedNames, drive, local, "address", "folder-uri", hashes, phoneReadable)

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

    @Test fun freeSpaceKeepsBoxWhenPhoneHasNoMp3() {
        val documentsOnly = setOf(FileKind.VTT, FileKind.MD)
        assertTrue(plan(DeletionMode.FREE_SPACE, listOf(recording(a, true, documentsOnly))).targets.isEmpty())
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

    // C1 : la copie Drive doit correspondre à l'empreinte actuelle du fichier du téléphone.

    private fun documentsOnDrive(): Pair<List<DriveFile>, LocalState> {
        val files = listOf(driveFile(a, FileKind.VTT), driveFile(a, FileKind.MD))
        return files to configured.copy(driveReceipts = receipts(*files.toTypedArray()))
    }

    @Test fun freeSpaceKeepsPhoneWhenReceiptShaDiffersFromCurrentHash() {
        val (files, local) = documentsOnDrive()
        val rs = listOf(recording(a, true, FileKind.entries.toSet()))
        val p = plan(DeletionMode.FREE_SPACE, rs, listing(*files.toTypedArray()), local, hashes = currentHashes(rs, sha = "modifié"))
        assertEquals(listOf(DeletionTarget(a, true, false, emptyList())), p.targets)
    }

    @Test fun freeSpaceKeepsPhoneWhenOneDocumentChanged() {
        val (files, local) = documentsOnDrive()
        val rs = listOf(recording(a, true, FileKind.entries.toSet()))
        val hashes = currentHashes(rs).toMutableMap().apply { put(RecordName.fileName(RecordType.NOTE, a, FileKind.MD), "refait") }
        val p = plan(DeletionMode.FREE_SPACE, rs, listing(*files.toTypedArray()), local, hashes = hashes)
        assertEquals(listOf(DeletionTarget(a, true, false, emptyList())), p.targets)
    }

    @Test fun freeSpaceKeepsPhoneWithoutKnownHash() {
        val (files, local) = documentsOnDrive()
        val rs = listOf(recording(a, true, FileKind.entries.toSet()))
        val p = plan(DeletionMode.FREE_SPACE, rs, listing(*files.toTypedArray()), local, hashes = emptyMap())
        assertEquals(listOf(DeletionTarget(a, true, false, emptyList())), p.targets)
    }

    @Test fun freeSpaceUsesConfirmedCopyForDocumentAbsentFromPhone() {
        val (files, local) = documentsOnDrive()
        val rs = listOf(recording(a, true, setOf(FileKind.MP3, FileKind.MD)))
        val p = plan(DeletionMode.FREE_SPACE, rs, listing(*files.toTypedArray()), local)
        assertEquals(listOf(DeletionTarget(a, true, true, emptyList())), p.targets)
    }

    @Test fun freeSpaceKeepsPhoneWhenDriveIsDisabled() {
        val (files, local) = documentsOnDrive()
        val rs = listOf(recording(a, true, FileKind.entries.toSet()))
        val p = plan(DeletionMode.FREE_SPACE, rs, listing(*files.toTypedArray()), local.copy(driveEnabled = false))
        assertEquals(listOf(DeletionTarget(a, true, false, emptyList())), p.targets)
    }

    // C2 : un MP3 en erreur de récupération n'est jamais une garantie.

    @Test fun freeSpaceNeverTargetsRecordingWithRecoverError() {
        val (files, local) = documentsOnDrive()
        val failed = local.copy(errors = mapOf(a to mapOf(Step.RECOVER to "interrompue")))
        val rs = listOf(recording(a, true, FileKind.entries.toSet()))
        assertTrue(plan(DeletionMode.FREE_SPACE, rs, listing(*files.toTypedArray()), failed).targets.isEmpty())
        assertTrue(plan(DeletionMode.FREE_SPACE, rs, null, configured.copy(errors = failed.errors)).targets.isEmpty())
    }

    @Test fun freeSpaceIgnoresErrorsOfOtherStepsAndOtherRecordings() {
        val local = configured.copy(errors = mapOf(a to mapOf(Step.SEND to "échec"), b to mapOf(Step.RECOVER to "interrompue")))
        val rs = listOf(recording(a, true, setOf(FileKind.MP3)))
        assertEquals(listOf(DeletionTarget(a, true, false, emptyList())), plan(DeletionMode.FREE_SPACE, rs, local = local).targets)
    }

    @Test fun everywhereIgnoresRecoverError() {
        val local = configured.copy(errors = mapOf(a to mapOf(Step.RECOVER to "interrompue")))
        val p = plan(DeletionMode.EVERYWHERE, listOf(recording(a, true, setOf(FileKind.MP3))), local = local)
        assertEquals(listOf(DeletionTarget(a, true, true, emptyList())), p.targets)
    }

    // I4 : le MP3 du boîtier n'est effacé que si un MP3 complet existe ailleurs.

    @Test fun freeSpaceKeepsBoxMp3WhenOnlyDocumentsAreOnPhoneEvenIfDocumentsAreOnDrive() {
        val (files, local) = documentsOnDrive()
        val rs = listOf(recording(a, true, setOf(FileKind.VTT, FileKind.MD)))
        val p = plan(DeletionMode.FREE_SPACE, rs, listing(*files.toTypedArray()), local)
        assertEquals(listOf(DeletionTarget(a, false, true, emptyList())), p.targets)
    }

    @Test fun freeSpaceFreesBoxWhenMp3IsProvenOnDrive() {
        val mp3 = driveFile(a, FileKind.MP3)
        val rs = listOf(recording(a, true, setOf(FileKind.VTT, FileKind.MD)))
        val p = plan(DeletionMode.FREE_SPACE, rs, listing(mp3), configured)
        assertEquals(listOf(DeletionTarget(a, true, false, emptyList())), p.targets)
    }

    @Test fun freeSpaceKeepsBoxWhenMp3OnDriveIsInAnotherDestinationOrListIsMissing() {
        val mp3 = driveFile(a, FileKind.MP3)
        val rs = listOf(recording(a, true, setOf(FileKind.VTT, FileKind.MD)))
        assertTrue(plan(DeletionMode.FREE_SPACE, rs, listing(mp3), LocalState(driveEnabled = true)).targets.isEmpty())
        assertTrue(plan(DeletionMode.FREE_SPACE, rs, listing(mp3), configured.copy(driveEnabled = false)).targets.isEmpty())
        assertTrue(plan(DeletionMode.FREE_SPACE, rs, null, configured).targets.isEmpty())
    }

    /** Décision de Geoff (spec) : des documents prouvés à jour sur Drive suffisent à libérer le téléphone, même au prix du MP3. */
    @Test fun freeSpaceWithProvenDocumentsFreesPhoneAndBoxEvenWithoutMp3OnDrive() {
        val (files, local) = documentsOnDrive()
        val rs = listOf(recording(a, true, FileKind.entries.toSet()))
        val p = plan(DeletionMode.FREE_SPACE, rs, listing(*files.toTypedArray()), local)
        assertEquals(listOf(DeletionTarget(a, true, true, emptyList())), p.targets)
    }

    // I2, M1, M3

    @Test fun everywhereFlagsUnreadableDriveWhenEnabledWithFolderAndNoListing() {
        assertTrue(plan(DeletionMode.EVERYWHERE, listOf(recording(a, true)), null).driveUnreadable)
    }

    @Test fun driveIsNotUnreadableWhenListedDisabledOrWithoutFolder() {
        val rs = listOf(recording(a, true))
        assertFalse(plan(DeletionMode.EVERYWHERE, rs, listing()).driveUnreadable)
        assertFalse(plan(DeletionMode.EVERYWHERE, rs, null, configured.copy(driveEnabled = false)).driveUnreadable)
        assertFalse(plan(DeletionMode.EVERYWHERE, rs, null, LocalState(driveEnabled = true)).driveUnreadable)
        assertFalse(plan(DeletionMode.FREE_SPACE, rs, null).driveUnreadable)
    }

    @Test fun planFlagsUnreadablePhoneFolder() {
        val rs = listOf(recording(a, true))
        assertTrue(plan(DeletionMode.EVERYWHERE, rs, phoneReadable = false).phoneUnreadable)
        assertFalse(plan(DeletionMode.EVERYWHERE, rs).phoneUnreadable)
    }

    @Test fun planLabelsTargetsWithSubjectOrDate() {
        val rs = listOf(recording(a, true), recording(b, true))
        val local = configured.copy(recordingSubjects = mapOf(a to "Réunion budget"))
        val labels = plan(DeletionMode.EVERYWHERE, rs, local = local).labels
        assertEquals("Réunion budget", labels[a])
        assertEquals(RecordName.rowTitle(RecordType.NOTE, b), labels[b])
    }

    // I1 : au moment d'exécuter, seul ce qui reste valide dans le plan frais part.

    private fun confirmedPlan(targets: List<DeletionTarget>) =
        UnifiedDeletionPlan(DeletionMode.FREE_SPACE, targets, emptySet(), false, "address", "folder-uri")

    @Test fun restrictKeepsOnlyLevelsStillTargeted() {
        val vtt = driveFile(a, FileKind.VTT)
        val confirmed = confirmedPlan(listOf(DeletionTarget(a, true, true, listOf(vtt)), DeletionTarget(b, true, false, emptyList())))
        val fresh = confirmedPlan(listOf(DeletionTarget(a, true, false, emptyList())))
        val result = UnifiedDeletion.restrict(confirmed, fresh)
        assertEquals(listOf(DeletionTarget(a, true, false, emptyList())), result.plan.targets)
        assertEquals(listOf("$a : téléphone", "$a : Drive", "$b : boîtier"), result.dropped)
    }

    @Test fun restrictNeverAddsTargetsAbsentFromConfirmation() {
        val confirmed = confirmedPlan(listOf(DeletionTarget(a, false, true, emptyList())))
        val fresh = confirmedPlan(listOf(DeletionTarget(a, true, true, emptyList()), DeletionTarget(b, true, true, emptyList())))
        val result = UnifiedDeletion.restrict(confirmed, fresh)
        assertEquals(listOf(DeletionTarget(a, false, true, emptyList())), result.plan.targets)
        assertTrue(result.dropped.isEmpty())
    }

    @Test fun restrictKeepsIdenticalPlanUntouched() {
        val confirmed = confirmedPlan(listOf(DeletionTarget(a, true, true, emptyList())))
        val result = UnifiedDeletion.restrict(confirmed, confirmed)
        assertEquals(confirmed, result.plan)
        assertTrue(result.dropped.isEmpty())
    }

    @Test fun restrictKeepsConfirmedMetadata() {
        val confirmed = confirmedPlan(listOf(DeletionTarget(a, true, true, emptyList()))).copy(driveUnreadable = true, phoneUnreadable = true)
        val result = UnifiedDeletion.restrict(confirmed, confirmedPlan(emptyList()))
        assertTrue(result.plan.targets.isEmpty())
        assertTrue(result.plan.driveUnreadable && result.plan.phoneUnreadable)
        assertEquals(listOf("$a : boîtier", "$a : téléphone"), result.dropped)
    }
}
