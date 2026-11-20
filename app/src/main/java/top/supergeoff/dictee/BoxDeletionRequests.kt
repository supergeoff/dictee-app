package top.supergeoff.dictee

/** A confirmation consumes the exact plan displayed to the user once. */
class BoxDeletionRequests {
    var current: BoxDeletionPlan? = null
        private set
    var executing: Boolean = false
        private set

    @Synchronized fun open(plan: BoxDeletionPlan) { if (!executing) current = plan }
    @Synchronized fun cancel() { current = null }
    @Synchronized fun confirm(): BoxDeletionPlan? {
        if (executing) return null
        val plan = current ?: return null
        current = null
        executing = true
        return plan
    }
    @Synchronized fun finish() { executing = false }
}
