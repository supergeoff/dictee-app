package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Test

class BoxDeletionRequestsTest {
    @Test fun cancelAndSingleConfirmation() {
        val plan = BoxDeletionPlan("address", listOf("a"), emptySet(), 1)
        val requests = BoxDeletionRequests()
        requests.open(plan); requests.cancel(); assertNull(requests.confirm())
        requests.open(plan); assertSame(plan, requests.confirm()); assertTrue(requests.executing)
        assertNull(requests.confirm()); requests.open(plan); assertNull(requests.current)
        requests.finish(); requests.open(plan); assertSame(plan, requests.confirm())
    }
}
