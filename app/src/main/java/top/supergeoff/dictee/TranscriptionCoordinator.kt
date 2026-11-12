package top.supergeoff.dictee

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Résultat d'une intervention bornée sur la file de transcription. */
sealed interface RunOutcome {
    data object Finished : RunOutcome
    data object Resume : RunOutcome
    data object Suspended : RunOutcome
    data object Failed : RunOutcome
}

/** Une instance partagée par le service et le worker sérialise leurs interventions. */
class TranscriptionCoordinator(
    private val repository: LocalStateRepository,
    private val client: TranscriptionClient,
    private val settings: TranscriptionSettingsManager,
    private val recoverySafety: RecoverySafety,
    private val verifyAudio: suspend (TranscriptionTask) -> Boolean,
    private val prepare: suspend (Set<String>?) -> List<TranscriptionTask> = { emptyList() },
    private val schedule: suspend (String) -> Unit = { error("Planification indisponible") },
    private val launch: suspend () -> Unit = {},
) {
    private val mutex = Mutex()

    /** Rétablit la programmation durable sans effectuer de requête au serveur. */
    suspend fun reconcile() = mutex.withLock {
        val tasks = repository.awaitInitialized().transcriptionTasks.filterNot { it.phase.isTerminal() }
        for (task in tasks) schedule(task.localId)
    }

    suspend fun start(names: Set<String>?): List<String> = mutex.withLock {
        recoverySafety.withTransferLock {
            val existing = repository.awaitInitialized().transcriptionTasks
            val active = existing.filterNot { it.phase.isTerminal() }.map { it.recordingName }.toSet()
            val tasks = prepare(names).filter { it.recordingName !in active }.sortedBy { it.recordingName }
            for (task in tasks) schedule(task.localId)
            if (tasks.isNotEmpty()) {
                repository.updateDurably { it.copy(transcriptionTasks = it.transcriptionTasks + tasks) }
                launch()
            }
            tasks.map { it.localId }
        }
    }

    suspend fun runBounded(localId: String): RunOutcome = mutex.withLock {
        recoverySafety.withTransferLock { runLocked(localId) }
    }

    private suspend fun runLocked(localId: String): RunOutcome {
        val state = repository.awaitInitialized()
        val task = state.transcriptionTasks.firstOrNull { it.localId == localId } ?: return RunOutcome.Resume
        if (task.phase.isTerminal()) return RunOutcome.Finished
        if (task.suspended) return RunOutcome.Suspended
        val first = state.transcriptionTasks.filter { !it.phase.isTerminal() && !it.suspended }
            .minByOrNull { it.recordingName }
        if (first?.localId != localId) return RunOutcome.Resume

        if (task.phase == TranscriptionPhase.SUBMITTING) {
            update(localId) { it.copy(phase = TranscriptionPhase.UNKNOWN_SUBMISSION, suspended = true,
                suspensionReason = "L'envoi a été interrompu. Vérifie la tâche avant de la relancer.") }
            return RunOutcome.Suspended
        }
        if (task.phase == TranscriptionPhase.UNKNOWN_SUBMISSION) return RunOutcome.Suspended
        if (task.phase != TranscriptionPhase.PENDING && task.phase != TranscriptionPhase.TRACKING) return RunOutcome.Resume
        if (settings.readForTask(localId) == null) return RunOutcome.Suspended

        return if (task.phase == TranscriptionPhase.PENDING) submit(task) else track(task)
    }

    private suspend fun submit(task: TranscriptionTask): RunOutcome {
        val verified = try { verifyAudio(task) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        if (!verified) {
            update(task.localId) { it.copy(suspended = true,
                suspensionReason = "Le MP3 a changé ou n'est plus accessible. Vérifie le fichier avant de relancer.") }
            return RunOutcome.Suspended
        }
        val submitting = update(task.localId) { it.copy(phase = TranscriptionPhase.SUBMITTING) }
        return try {
            when (val result = client.submit(submitting)) {
                is SubmitResult.Accepted -> applyServer(task.localId, result.task)
                is SubmitResult.DirectVtt -> {
                    // La conservation durable du VTT direct sera raccordée à la sauvegarde.
                    update(task.localId) { it.copy(phase = TranscriptionPhase.UNKNOWN_SUBMISSION, suspended = true,
                        suspensionReason = "La réponse directe ne peut pas encore être enregistrée.") }
                    RunOutcome.Suspended
                }
                SubmitResult.BackgroundUnsupported -> {
                    update(task.localId) { it.copy(phase = TranscriptionPhase.FAILED,
                        error = "Le serveur ne prend pas en charge les tâches en arrière-plan.") }
                    RunOutcome.Failed
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranscriptionHttpException) {
            update(task.localId) { it.copy(phase = TranscriptionPhase.FAILED, error = e.message) }
            RunOutcome.Failed
        } catch (e: CredentialException) {
            update(task.localId) { it.copy(phase = TranscriptionPhase.PENDING, suspended = true,
                suspensionReason = "Clé inaccessible. Vérifie les réglages de transcription.") }
            RunOutcome.Suspended
        } catch (e: Exception) {
            // Sans réponse fiable, le serveur peut avoir accepté le MP3.
            update(task.localId) { it.copy(phase = TranscriptionPhase.UNKNOWN_SUBMISSION, suspended = true,
                suspensionReason = "Résultat de l'envoi inconnu. Vérifie la tâche avant de la relancer.") }
            RunOutcome.Suspended
        }
    }

    private suspend fun track(task: TranscriptionTask): RunOutcome = try {
        applyServer(task.localId, client.get(task))
    } catch (e: CancellationException) {
        throw e
    } catch (e: TranscriptionHttpException) {
        if (e.code == 404) {
            update(task.localId) { it.copy(suspended = true,
                suspensionReason = "Tâche introuvable sur le serveur. Vérifie le compte ou relance la transcription.") }
            RunOutcome.Suspended
        } else RunOutcome.Resume
    } catch (_: TranscriptionClientException) {
        RunOutcome.Resume
    }

    private suspend fun applyServer(localId: String, server: ServerTask): RunOutcome {
        val phase = when (server.status) {
            ServerStatus.QUEUED, ServerStatus.IN_PROGRESS -> TranscriptionPhase.TRACKING
            ServerStatus.COMPLETED -> TranscriptionPhase.SAVING
            ServerStatus.FAILED -> TranscriptionPhase.FAILED
            ServerStatus.CANCELLED -> TranscriptionPhase.CANCELLED
        }
        update(localId) { it.copy(phase = phase, serverId = server.id, progress = server.progress) }
        return when (phase) {
            TranscriptionPhase.FAILED -> RunOutcome.Failed
            TranscriptionPhase.CANCELLED -> RunOutcome.Finished
            else -> RunOutcome.Resume
        }
    }

    private suspend fun update(localId: String, change: (TranscriptionTask) -> TranscriptionTask): TranscriptionTask {
        val state = repository.updateDurably { current ->
            current.copy(transcriptionTasks = current.transcriptionTasks.map { if (it.localId == localId) change(it) else it })
        }
        return state.transcriptionTasks.first { it.localId == localId }
    }
}
