package top.supergeoff.dictee

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class BoxDeletionJobTest {
    private val a = "20261117120000"
    private val b = "20261117120100"
    private val c = "20261117120200"
    private fun entry(name: String) = ListItem.Entry(name, 1, 1)

    @Test fun successAbsenceAndRefusalContinueInOrder() = runBlocking {
        val calls = mutableListOf<String>()
        var current = ""
        val job = BoxDeletionJob({ current = it; calls += "delete:$it"; if (it == b) 1 else 0 },
            { calls += "list:$current"; if (current == b) emptyList() else listOf(entry(b)) },
            { calls += "apply:${it.size}" })
        val result = job.run(listOf(a, b))
        assertEquals(setOf(a), result.confirmed); assertEquals(mapOf(b to 1), result.refused)
        assertTrue(result.unverified.isEmpty()); assertTrue(result.untreated.isEmpty()); assertNull(result.error)
        assertEquals(listOf("delete:$a", "list:$a", "apply:1", "delete:$b", "list:$b", "apply:0"), calls)
    }
    @Test fun successStillPresentStopsBatch() = runBlocking {
        val calls = mutableListOf<String>()
        val result = BoxDeletionJob({ calls += it; 0 }, { listOf(entry(a)) }, { calls += "applied" }).run(listOf(a, b))
        assertEquals(setOf(a), result.unverified); assertEquals(setOf(b), result.untreated)
        assertTrue(result.confirmed.isEmpty()); assertEquals(listOf(a, "applied"), calls)
    }
    @Test fun refusalWithPresenceStillAllowsNext() = runBlocking {
        val sent = mutableListOf<String>()
        val result = BoxDeletionJob({ sent += it; if (it == a) 1 else 0 }, { listOf(entry(a)) }, {}).run(listOf(a, b))
        assertEquals(mapOf(a to 1), result.refused); assertEquals(setOf(b), result.confirmed)
        assertEquals(listOf(a, b), sent)
    }
    @Test fun partialBatchStopsWithoutRetry() = runBlocking {
        for (message in listOf("sans statut", "liaison coupée", "erreur écriture")) {
            val sent = mutableListOf<String>(); val applied = mutableListOf<List<ListItem.Entry>>()
            val result = BoxDeletionJob({ sent += it; if (it == b) throw SessionException(message); 0 },
                { listOf(entry(b), entry(c)) }, { applied += it }).run(listOf(a, b, c))
            assertEquals(setOf(a), result.confirmed); assertEquals(setOf(b), result.unverified)
            assertEquals(setOf(c), result.untreated); assertEquals(message, result.error)
            assertEquals(listOf(a, b), sent); assertEquals(1, applied.size)
        }
    }
    @Test fun incompleteListStopsAndNeverAppliesPartialList() = runBlocking {
        val sent = mutableListOf<String>(); val applied = mutableListOf<List<ListItem.Entry>>()
        var lists = 0
        val result = BoxDeletionJob({ sent += it; 0 }, {
            if (++lists == 2) throw SessionException("liste incomplète : pas de fin de liste")
            listOf(entry(b), entry(c))
        }, { applied += it }).run(listOf(a, b, c))
        assertEquals(setOf(a), result.confirmed); assertEquals(setOf(b), result.unverified)
        assertEquals(setOf(c), result.untreated); assertEquals(listOf(a, b), sent); assertEquals(1, applied.size)
    }
    @Test fun refusalSurvivesListingFailure() = runBlocking {
        val result = BoxDeletionJob({ 1 }, { throw SessionException("liste incomplète") }, {}).run(listOf(a, b))
        assertEquals(mapOf(a to 1), result.refused); assertTrue(result.unverified.isEmpty()); assertEquals(setOf(b), result.untreated)
    }
    @Test fun cancellationPropagatesWithoutRetry() = runBlocking {
        val sent = mutableListOf<String>(); var released = false
        try {
            try {
                BoxDeletionJob({ sent += it; throw CancellationException("cancel") }, { emptyList() }, {}).run(listOf(a, b))
                fail("cancellation expected")
            } finally { released = true }
        } catch (_: CancellationException) { }
        assertTrue(released); assertEquals(listOf(a), sent)
    }
}
