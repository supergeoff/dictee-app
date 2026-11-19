package top.supergeoff.dictee

data class DeletionSnapshot(
    val folder: String?, val recordings: List<Recording>, val protectedNames: Set<String>,
    val drive: DriveListing?, val local: LocalState, val hashes: Map<String, String>,
)

sealed interface DeletionOutcome {
    data object FolderChanged : DeletionOutcome
    data class Blocked(val names: Set<String>) : DeletionOutcome
    data class Completed(val result: PhoneDeletionResult, val readError: String? = null) : DeletionOutcome
}

class PhoneDeletionOrchestrator(
    private val withTransferLock: suspend (suspend () -> DeletionOutcome) -> DeletionOutcome,
    private val withPhoneLock: suspend (suspend () -> DeletionOutcome) -> DeletionOutcome,
    private val snapshot: suspend () -> DeletionSnapshot,
    private val delete: suspend (PhoneDeletionPlan) -> PhoneDeletionResult,
    private val reread: suspend (PhoneDeletionResult) -> Unit,
) {
    suspend fun execute(folder: String, names: Set<String>): DeletionOutcome = withTransferLock {
        withPhoneLock {
            val current = snapshot()
            if (current.folder != folder) return@withPhoneLock DeletionOutcome.FolderChanged
            val blocked = names intersect current.protectedNames
            if (blocked.isNotEmpty()) return@withPhoneLock DeletionOutcome.Blocked(blocked)
            val plan = PhoneDeletion.plan(folder, current.recordings, names, current.protectedNames,
                current.drive, current.local, current.hashes)
            val result = delete(plan)
            val readError = try { reread(result); null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            DeletionOutcome.Completed(result, readError)
        }
    }
}

/** A confirmation consumes the exact plan displayed to the user once. */
class PhoneDeletionRequests {
    var current: PhoneDeletionPlan? = null
        private set
    var executing: Boolean = false
        private set

    @Synchronized fun open(plan: PhoneDeletionPlan) { if (!executing) current = plan }
    @Synchronized fun cancel() { current = null }
    @Synchronized fun confirm(): PhoneDeletionPlan? {
        if (executing) return null
        val plan = current ?: return null
        current = null
        executing = true
        return plan
    }
    @Synchronized fun finish() { executing = false }
}
