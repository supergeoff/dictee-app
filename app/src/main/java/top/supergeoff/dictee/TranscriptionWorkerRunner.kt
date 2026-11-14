package top.supergeoff.dictee

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Résultat d'une intervention, distinct des phases conservées dans state.json. */
internal enum class WorkerRun { Finished, Retry }

/** Politique commune au worker Android : une intervention bornée, sans lancer de service. */
internal class TranscriptionWorkerRunner(
    private val repository: LocalStateRepository,
    private val coordinator: TranscriptionCoordinator,
    private val cancelScheduled: suspend (String) -> Unit,
    private val timeoutMs: Long = TRANSCRIPTION_TIMEOUT_MS,
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

    suspend fun retireIfTerminal(localId: String) {
        val task = repository.awaitInitialized().transcriptionTasks.firstOrNull { it.localId == localId }
        if (task?.phase?.isTerminal() == true) cancelScheduled(localId)
    }

    companion object { private const val MAX_MISSING_ATTEMPTS = 12 }
}

internal const val TRANSCRIPTION_TIMEOUT_MS = 9 * 60 * 1000L
private const val TRANSCRIPTION_POLL_MS = 5_000L

internal enum class InteractiveRun { QueueEmpty, Handoff }

/** Suit la même file durable que le worker ; une modification de la file peut interrompre l'attente. */
internal class TranscriptionInteractiveRunner(
    private val repository: LocalStateRepository,
    private val coordinator: TranscriptionCoordinator,
    private val worker: TranscriptionWorkerRunner,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = TRANSCRIPTION_TIMEOUT_MS,
    private val wait: suspend (TranscriptionTask, Long) -> Unit = { task, millis ->
        withTimeoutOrNull(millis) {
            repository.state.filterNotNull().first { state ->
                val active = state.transcriptionTasks.filter { !it.phase.isTerminal() && !it.suspended }
                active.minByOrNull { it.recordingName } != task
            }
        }
        Unit
    },
) {
    suspend fun run(): InteractiveRun {
        while (true) {
            val next = repository.awaitInitialized().transcriptionTasks.filter { !it.phase.isTerminal() && !it.suspended }
                .minByOrNull { it.recordingName } ?: return InteractiveRun.QueueEmpty
            val outcome = withTimeoutOrNull(timeoutMs) { coordinator.runBounded(next.localId) } ?: return InteractiveRun.Handoff
            worker.retireIfTerminal(next.localId)
            val current = repository.awaitInitialized().transcriptionTasks.firstOrNull { it.localId == next.localId }
            if (outcome == RunOutcome.Suspended && current != null && !current.suspended) return InteractiveRun.Handoff
            if (outcome == RunOutcome.Resume && current != null && !current.phase.isTerminal() && !current.suspended) {
                val deadline = current.synthesis?.nextAttemptAtMillis.takeIf {
                    current.phase == TranscriptionPhase.SYNTHESIZING && current.synthesis?.phase == SynthesisPhase.READY
                }
                val delay = if (deadline == null) TRANSCRIPTION_POLL_MS else (deadline - clock()).coerceAtLeast(1_000L)
                wait(current, delay)
            }
        }
    }
}

internal fun transcriptionNotificationLabel(progress: Progress, tasks: List<TranscriptionTask>): String {
    val first = tasks.filter { !it.phase.isTerminal() && !it.suspended }.minByOrNull { it.recordingName }
    return if (first?.phase == TranscriptionPhase.SYNTHESIZING) progress.label().replaceFirst("Transcription", "Synthèse")
    else progress.label()
}
