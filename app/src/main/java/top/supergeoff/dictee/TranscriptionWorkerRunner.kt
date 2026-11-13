package top.supergeoff.dictee

import kotlinx.coroutines.withTimeoutOrNull

/** Résultat d'une intervention, distinct des phases conservées dans state.json. */
internal enum class WorkerRun { Finished, Retry }

/** Politique commune au worker Android : une intervention bornée, sans lancer de service. */
internal class TranscriptionWorkerRunner(
    private val repository: LocalStateRepository,
    private val coordinator: TranscriptionCoordinator,
    private val cancelScheduled: suspend (String) -> Unit,
    private val timeoutMs: Long = 9 * 60 * 1000L,
) {
    suspend fun run(localId: String, attempt: Int): WorkerRun {
        val outcome = withTimeoutOrNull(timeoutMs) { coordinator.runBounded(localId) } ?: return WorkerRun.Retry
        val task = repository.awaitInitialized().transcriptionTasks.firstOrNull { it.localId == localId }
        if (task == null) {
            if (attempt < MAX_MISSING_ATTEMPTS) return WorkerRun.Retry
            cancelScheduled(localId)
            return WorkerRun.Finished
        }
        if (task.phase.isTerminal()) cancelScheduled(localId)
        return when (outcome) {
            RunOutcome.Finished, RunOutcome.Resume, RunOutcome.Suspended, RunOutcome.Failed -> WorkerRun.Finished
        }
    }

    companion object { private const val MAX_MISSING_ATTEMPTS = 12 }
}
