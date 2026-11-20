package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Test

class UnifiedDeletionPresentationTest {
    private val a = "20261117120000"
    private val b = "20261117120100"
    private val file = DriveFile("id", "Note-$a.vtt", 1)

    private fun plan(
        mode: DeletionMode = DeletionMode.EVERYWHERE, targets: List<DeletionTarget>, blocked: Set<String> = emptySet(),
        driveSkipped: Boolean = false, driveUnreadable: Boolean = false, phoneUnreadable: Boolean = false,
        labels: Map<String, String> = emptyMap(), mp3Lost: Set<String> = emptySet(),
    ) = UnifiedDeletionPlan(mode, targets, blocked, driveSkipped, "address", "folder-uri", driveUnreadable, phoneUnreadable, labels, mp3Lost)

    @Test fun titles() {
        assertEquals("Supprimer partout ?", unifiedDeletionTitle(plan(targets = emptyList())))
        assertEquals("Libérer l'espace ?", unifiedDeletionTitle(plan(DeletionMode.FREE_SPACE, emptyList())))
    }

    @Test fun everywhereBody() {
        val p = plan(targets = listOf(DeletionTarget(a, true, true, listOf(file)), DeletionTarget(b, true, false, emptyList())))
        assertEquals(listOf("2 enregistrements seront effacés : 2 du boîtier, 1 du téléphone, 1 de Drive."), unifiedDeletionBody(p))
    }

    @Test fun singularAndZeroLevelsOmitted() {
        val p = plan(targets = listOf(DeletionTarget(a, false, true, emptyList())))
        assertEquals(listOf("1 enregistrement sera effacé : 1 du téléphone."), unifiedDeletionBody(p))
    }

    @Test fun freeSpaceBodyMentionsDriveKept() {
        val p = plan(DeletionMode.FREE_SPACE, listOf(DeletionTarget(a, true, false, emptyList())))
        assertEquals(
            listOf("1 enregistrement sera effacé : 1 du boîtier.", "Les copies Drive sont conservées."),
            unifiedDeletionBody(p),
        )
    }

    @Test fun confirmationMentionsDriveSkipped() {
        val p = plan(targets = listOf(DeletionTarget(a, true, false, emptyList())), driveSkipped = true)
        assertEquals(
            listOf("1 enregistrement sera effacé : 1 du boîtier.", "Drive est désactivé : ses copies ne sont pas touchées."),
            unifiedDeletionBody(p),
        )
    }

    @Test fun freeSpaceWarnsWhenMp3IsLost() {
        val p = plan(DeletionMode.FREE_SPACE, listOf(DeletionTarget(a, true, true, emptyList())), mp3Lost = setOf(a))
        assertEquals(
            listOf(
                "1 enregistrement sera effacé : 1 du boîtier, 1 du téléphone.",
                "Les copies Drive sont conservées.",
                "Le MP3 ne sera plus conservé nulle part.",
            ),
            unifiedDeletionBody(p),
        )
    }

    @Test fun freeSpaceCountsRecordingsWhoseMp3IsLost() {
        val p = plan(
            DeletionMode.FREE_SPACE,
            listOf(DeletionTarget(a, true, true, emptyList()), DeletionTarget(b, true, true, emptyList())),
            mp3Lost = setOf(a, b),
        )
        assertEquals(
            listOf(
                "2 enregistrements seront effacés : 2 du boîtier, 2 du téléphone.",
                "Les copies Drive sont conservées.",
                "Le MP3 de 2 enregistrements ne sera plus conservé nulle part.",
            ),
            unifiedDeletionBody(p),
        )
    }

    @Test fun noMp3LineWhenMp3IsKeptOnDriveOrPhoneIsKept() {
        val mp3KeptOnDrive = plan(DeletionMode.FREE_SPACE, listOf(DeletionTarget(a, true, true, emptyList())))
        assertEquals(
            listOf("1 enregistrement sera effacé : 1 du boîtier, 1 du téléphone.", "Les copies Drive sont conservées."),
            unifiedDeletionBody(mp3KeptOnDrive),
        )
        val phoneKept = plan(DeletionMode.FREE_SPACE, listOf(DeletionTarget(a, true, false, emptyList())))
        assertEquals(
            listOf("1 enregistrement sera effacé : 1 du boîtier.", "Les copies Drive sont conservées."),
            unifiedDeletionBody(phoneKept),
        )
    }

    @Test fun blockedBodyNamesBusyRecordings() {
        val p = plan(
            targets = listOf(DeletionTarget(a, true, false, emptyList()), DeletionTarget(b, true, false, emptyList())),
            blocked = linkedSetOf(a, b),
        )
        assertEquals(
            "Enregistrements occupés : $a, $b. Attends la fin de leur traitement.",
            unifiedDeletionBody(p).last(),
        )
    }

    @Test fun blockedCannotConfirm() {
        val targets = listOf(DeletionTarget(a, true, false, emptyList()))
        assertTrue(unifiedDeletionCanConfirm(plan(targets = targets), executing = false))
        assertFalse(unifiedDeletionCanConfirm(plan(targets = targets, blocked = setOf(a)), executing = false))
        assertFalse(unifiedDeletionCanConfirm(plan(targets = targets), executing = true))
        assertFalse(unifiedDeletionCanConfirm(plan(targets = emptyList()), executing = false))
    }

    @Test fun failureTextNullWhenNoFailure() {
        assertNull(unifiedDeletionFailureText(emptyList()))
    }

    @Test fun failureTextNamesFirstFailure() {
        assertEquals(
            "Suppression incomplète : boîtier hors de portée (voir le journal)",
            unifiedDeletionFailureText(listOf("boîtier hors de portée", "autre")),
        )
    }

    @Test fun confirmationMentionsUnreadableDrive() {
        val p = plan(targets = listOf(DeletionTarget(a, true, false, emptyList())), driveUnreadable = true)
        assertEquals(
            listOf("1 enregistrement sera effacé : 1 du boîtier.", "Drive illisible : ses copies ne seront pas touchées."),
            unifiedDeletionBody(p),
        )
    }

    @Test fun confirmationMentionsUnreadablePhoneFolder() {
        val p = plan(targets = listOf(DeletionTarget(a, true, false, emptyList())), phoneUnreadable = true)
        assertEquals(
            listOf("1 enregistrement sera effacé : 1 du boîtier.", "Dossier du téléphone illisible : ses fichiers ne seront pas touchés."),
            unifiedDeletionBody(p),
        )
    }

    @Test fun singleTargetNamesTheRecording() {
        val p = plan(targets = listOf(DeletionTarget(a, true, false, emptyList())), labels = mapOf(a to "Réunion budget"))
        assertEquals(
            listOf("Enregistrement : Réunion budget.", "1 enregistrement sera effacé : 1 du boîtier."),
            unifiedDeletionBody(p),
        )
    }

    @Test fun severalTargetsDoNotNameRecordings() {
        val p = plan(
            targets = listOf(DeletionTarget(a, true, false, emptyList()), DeletionTarget(b, true, false, emptyList())),
            labels = mapOf(a to "Réunion budget", b to "Autre"),
        )
        assertEquals(listOf("2 enregistrements seront effacés : 2 du boîtier."), unifiedDeletionBody(p))
    }

    @Test fun noticePrefersFailureThenReducedPlan() {
        assertNull(unifiedDeletionNotice(emptyList(), emptyList()))
        assertEquals(
            "Suppression incomplète : Drive : liste illisible (voir le journal)",
            unifiedDeletionNotice(listOf("Drive : liste illisible"), listOf("$a : boîtier")),
        )
        assertEquals(
            "Suppression réduite : l'état a changé depuis la confirmation (voir le journal)",
            unifiedDeletionNotice(emptyList(), listOf("$a : boîtier")),
        )
    }
}
