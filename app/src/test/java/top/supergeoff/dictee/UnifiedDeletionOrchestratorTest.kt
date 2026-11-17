package top.supergeoff.dictee

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CancellationException

class UnifiedDeletionOrchestratorTest {
    private val a = "20261117120000"
    private val b = "20261117120100"
    private val vtt = DriveFile("id-vtt", "Note-$a.vtt", 10)
    private val md = DriveFile("id-md", "Note-$a.md", 10)

    private fun plan(
        mode: DeletionMode = DeletionMode.EVERYWHERE,
        targets: List<DeletionTarget>,
        blocked: Set<String> = emptySet(),
    ) = UnifiedDeletionPlan(mode, targets, blocked, false, "AA:BB", "content://folder")

    private val everywhere = plan(targets = listOf(
        DeletionTarget(a, box = true, phone = true, driveFiles = listOf(vtt, md)),
        DeletionTarget(b, box = true, phone = false, driveFiles = emptyList()),
    ))

    private fun boxResult(confirmed: Set<String>, error: String? = null, refused: Map<String, Int> = emptyMap()) =
        BoxDeletionResult(confirmed, refused, emptySet(), emptySet(), error)

    @Test fun executesDriveThenPhoneThenBox() = runBlocking {
        val calls = mutableListOf<String>()
        val outcome = UnifiedDeletionOrchestrator(
            trashDrive = { calls += "drive:${it.id}" },
            deletePhone = { names -> calls += "phone:${names.sorted()}"; PhoneDeletionResult(setOf("Note-$a.mp3"), emptyMap()) },
            deleteBox = { names -> calls += "box:$names"; boxResult(names.toSet()) },
            onDriveTrashed = { calls += "receipts:${it.sorted()}" },
        ).execute(everywhere)
        assertEquals(listOf("drive:id-vtt", "drive:id-md", "receipts:[id-md, id-vtt]", "phone:[$a]", "box:[$a, $b]"), calls)
        assertTrue(outcome.failures.isEmpty())
        assertEquals(setOf(vtt.name, md.name, "Note-$a.mp3", a, b), outcome.deleted)
    }

    @Test fun blockedPlanDeletesNothing() = runBlocking {
        val calls = mutableListOf<String>()
        val outcome = UnifiedDeletionOrchestrator(
            { calls += "drive" }, { calls += "phone"; null }, { calls += "box"; boxResult(emptySet()) }, { calls += "receipts" },
        ).execute(everywhere.copy(blockedNames = setOf(a)))
        assertTrue(calls.isEmpty())
        assertTrue(outcome.deleted.isEmpty())
        assertTrue(outcome.failures.isEmpty())
    }

    @Test fun boxUnavailableStillDeletesOtherLevels() = runBlocking {
        val calls = mutableListOf<String>()
        val outcome = UnifiedDeletionOrchestrator(
            { calls += "drive:${it.id}" },
            { calls += "phone"; PhoneDeletionResult(setOf("Note-$a.mp3"), emptyMap()) },
            { throw IllegalStateException("hors de portée") },
            { calls += "receipts" },
        ).execute(everywhere)
        assertEquals(listOf("drive:id-vtt", "drive:id-md", "receipts", "phone"), calls)
        assertEquals(listOf("Boîtier : hors de portée"), outcome.failures)
        assertEquals(setOf(vtt.name, md.name, "Note-$a.mp3"), outcome.deleted)
    }

    @Test fun driveFailureContinues() = runBlocking {
        val trashed = mutableListOf<String>()
        val receipts = mutableListOf<Set<String>>()
        var phoneCalled = false
        var boxCalled = false
        val outcome = UnifiedDeletionOrchestrator(
            { if (it.id == "id-vtt") throw DriveException(403, "accès refusé"); trashed += it.id },
            { phoneCalled = true; PhoneDeletionResult(emptySet(), emptyMap()) },
            { boxCalled = true; boxResult(it.toSet()) },
            { receipts += it },
        ).execute(everywhere)
        assertEquals(listOf("id-md"), trashed)
        assertEquals(listOf(setOf("id-md")), receipts)
        assertTrue(phoneCalled && boxCalled)
        assertEquals(1, outcome.failures.size)
        assertTrue(outcome.failures.single().startsWith("Drive : ${vtt.name} : "))
        assertTrue(outcome.failures.single().contains("accès refusé"))
    }

    @Test fun freeSpaceNeverCallsDrive() = runBlocking {
        val calls = mutableListOf<String>()
        val outcome = UnifiedDeletionOrchestrator(
            { calls += "drive" }, { names -> calls += "phone:$names"; PhoneDeletionResult(emptySet(), emptyMap()) },
            { calls += "box:$it"; boxResult(it.toSet()) }, { calls += "receipts" },
        ).execute(plan(DeletionMode.FREE_SPACE, listOf(
            DeletionTarget(a, box = true, phone = true, driveFiles = emptyList()),
            DeletionTarget(b, box = true, phone = false, driveFiles = emptyList()),
        )))
        assertEquals(listOf("phone:[$a]", "box:[$a, $b]"), calls)
        assertTrue(outcome.failures.isEmpty())
    }

    @Test fun phoneAndBoxFailuresAreReported() = runBlocking {
        val outcome = UnifiedDeletionOrchestrator(
            { }, { PhoneDeletionResult(emptySet(), mapOf("Note-$a.mp3" to "accès refusé")) },
            { boxResult(emptySet(), error = "liaison coupée", refused = mapOf(a to 2)) }, { },
        ).execute(everywhere)
        assertEquals(
            listOf("Téléphone : Note-$a.mp3 : accès refusé", "Boîtier : $a : refusé (code 2)", "Boîtier : liaison coupée"),
            outcome.failures,
        )
    }

    @Test fun skippedPhoneIsReported() = runBlocking {
        val outcome = UnifiedDeletionOrchestrator({ }, { null }, { boxResult(it.toSet()) }, { }).execute(everywhere)
        assertEquals(listOf("Téléphone : suppression annulée"), outcome.failures)
    }

    @Test fun cancellationPropagates() {
        val orchestrator = UnifiedDeletionOrchestrator({ }, { throw CancellationException() }, { boxResult(emptySet()) }, { })
        try {
            runBlocking { orchestrator.execute(everywhere) }
            fail("l'annulation doit remonter")
        } catch (_: CancellationException) {
        }
    }
}
