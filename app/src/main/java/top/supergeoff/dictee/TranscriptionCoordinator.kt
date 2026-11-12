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
    /** Écrit les octets exacts du VTT dans le dossier, en protégeant l'ancien fichier ; lève [PhoneStoreException] sinon. */
    private val saveVtt: suspend (TranscriptionTask, ByteArray) -> Unit = { _, _ -> error("Sauvegarde indisponible") },
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

    /**
     * L'intention d'annuler est persistée avant tout appel réseau, sans attendre le verrou : une soumission longue
     * ne la retarde pas, et [update] la préserve face aux réponses qui arrivent ensuite.
     */
    suspend fun requestCancel(localId: String) {
        repository.updateDurably { current ->
            current.copy(transcriptionTasks = current.transcriptionTasks.map { task ->
                if (task.localId != localId || task.phase.isTerminal()) task
                else task.copy(
                    phase = TranscriptionPhase.CANCEL_REQUESTED, suspended = false, suspensionReason = null,
                    error = if (task.serverId == null && task.phase in MAYBE_SENT) CANNOT_CONFIRM else task.error,
                )
            })
        }
        mutex.withLock { recoverySafety.withTransferLock { runLocked(localId) } }
    }

    suspend fun runBounded(localId: String): RunOutcome = mutex.withLock {
        recoverySafety.withTransferLock { runLocked(localId) }
    }

    private suspend fun runLocked(localId: String): RunOutcome {
        val state = repository.awaitInitialized()
        val task = state.transcriptionTasks.firstOrNull { it.localId == localId } ?: return RunOutcome.Resume
        if (task.phase.isTerminal()) return RunOutcome.Finished
        if (task.suspended) return RunOutcome.Suspended
        if (task.phase == TranscriptionPhase.CANCEL_REQUESTED) return cancelOnServer(task)
        val first = state.transcriptionTasks.filter { !it.phase.isTerminal() && !it.suspended && it.phase != TranscriptionPhase.CANCEL_REQUESTED }
            .minByOrNull { it.recordingName }
        if (first?.localId != localId) return RunOutcome.Resume

        if (task.phase == TranscriptionPhase.SUBMITTING) {
            update(localId) { it.copy(phase = TranscriptionPhase.UNKNOWN_SUBMISSION, suspended = true,
                suspensionReason = "L'envoi a été interrompu. Vérifie la tâche avant de la relancer.") }
            return RunOutcome.Suspended
        }
        if (task.phase == TranscriptionPhase.UNKNOWN_SUBMISSION) return RunOutcome.Suspended
        if (task.phase != TranscriptionPhase.PENDING && task.phase != TranscriptionPhase.TRACKING &&
            task.phase != TranscriptionPhase.SAVING) return RunOutcome.Resume
        if (settings.readForTask(localId) == null) return RunOutcome.Suspended
        if (task.phase == TranscriptionPhase.SAVING) return save(task.localId, null)

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
        if (submitting.phase == TranscriptionPhase.CANCEL_REQUESTED) return RunOutcome.Resume
        return try {
            when (val result = client.submit(submitting)) {
                is SubmitResult.Accepted -> applyServer(task.localId, result.task)
                is SubmitResult.DirectVtt -> {
                    update(task.localId) { it.copy(phase = TranscriptionPhase.SAVING) }
                    save(task.localId, result.bytes)
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

    private suspend fun cancelOnServer(task: TranscriptionTask): RunOutcome {
        if (task.serverId == null) {
            update(task.localId) { it.copy(phase = TranscriptionPhase.CANCELLED) }
            return RunOutcome.Finished
        }
        if (settings.readForTask(task.localId) == null) return RunOutcome.Suspended
        return try {
            val server = client.cancel(task)
            when (server.status) {
                // Même un résultat déjà prêt n'est pas enregistré : l'ancien VTT reste intact.
                ServerStatus.CANCELLED, ServerStatus.COMPLETED -> {
                    update(task.localId) { it.copy(phase = TranscriptionPhase.CANCELLED, error = null, progress = server.progress) }
                    RunOutcome.Finished
                }
                ServerStatus.FAILED -> {
                    update(task.localId) { it.copy(phase = TranscriptionPhase.FAILED, error = null) }
                    RunOutcome.Failed
                }
                ServerStatus.QUEUED, ServerStatus.IN_PROGRESS -> {
                    update(task.localId) { it.copy(progress = server.progress) }
                    RunOutcome.Resume
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranscriptionHttpException) {
            if (e.code == 404) {
                update(task.localId) { it.copy(suspended = true,
                    suspensionReason = "Tâche introuvable sur le serveur : l'annulation n'est pas confirmée. Vérifie le compte.") }
                RunOutcome.Suspended
            } else RunOutcome.Resume
        } catch (_: CredentialException) {
            RunOutcome.Suspended
        } catch (_: TranscriptionClientException) {
            RunOutcome.Resume
        }
    }

    private suspend fun applyServer(localId: String, server: ServerTask): RunOutcome {
        val phase = when (server.status) {
            ServerStatus.QUEUED, ServerStatus.IN_PROGRESS -> TranscriptionPhase.TRACKING
            ServerStatus.COMPLETED -> TranscriptionPhase.SAVING
            ServerStatus.FAILED -> TranscriptionPhase.FAILED
            ServerStatus.CANCELLED -> TranscriptionPhase.CANCELLED
        }
        val updated = update(localId) { it.copy(phase = phase, serverId = server.id, progress = server.progress) }
        if (phase == TranscriptionPhase.SAVING && updated.phase == TranscriptionPhase.SAVING) return save(localId, server.output)
        return when (phase) {
            TranscriptionPhase.FAILED -> RunOutcome.Failed
            TranscriptionPhase.CANCELLED -> RunOutcome.Finished
            else -> RunOutcome.Resume
        }
    }

    /**
     * Valide puis écrit les octets reçus. La phase est relue juste avant l'écriture : une annulation persistée entre-temps
     * empêche la promotion. Sans octets connus, le résultat est relu sur le serveur (conservé 24 heures).
     */
    private suspend fun save(localId: String, known: ByteArray?): RunOutcome {
        var task = repository.awaitInitialized().transcriptionTasks.first { it.localId == localId }
        val bytes = known ?: run {
            if (task.serverId == null) {
                update(localId) { it.copy(phase = TranscriptionPhase.FAILED,
                    error = "Le résultat n'est plus disponible. Relance la transcription.") }
                return RunOutcome.Failed
            }
            val server = try {
                client.get(task)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranscriptionHttpException) {
                if (e.code != 404) return RunOutcome.Resume
                update(localId) { it.copy(suspended = true,
                    suspensionReason = "Résultat introuvable sur le serveur. Vérifie le compte ou relance la transcription.") }
                return RunOutcome.Suspended
            } catch (_: TranscriptionClientException) {
                return RunOutcome.Resume
            }
            if (server.status != ServerStatus.COMPLETED || server.output == null) return applyServer(localId, server)
            server.output
        }
        try {
            WebVtt.parse(bytes)
        } catch (_: VttFormatException) {
            update(localId) { it.copy(phase = TranscriptionPhase.FAILED,
                error = "Le serveur a renvoyé un VTT invalide. L'ancien fichier est conservé.") }
            return RunOutcome.Failed
        }
        task = repository.awaitInitialized().transcriptionTasks.first { it.localId == localId }
        if (task.phase != TranscriptionPhase.SAVING) return RunOutcome.Resume
        try {
            saveVtt(task, bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PhoneStoreException) {
            update(localId) { it.copy(suspended = true, suspensionReason = e.message ?: "Écriture impossible dans le dossier.") }
            return RunOutcome.Suspended
        } catch (_: java.io.IOException) {
            return RunOutcome.Resume
        }
        update(localId) { it.copy(phase = TranscriptionPhase.COMPLETED, progress = 1.0, error = null) }
        return RunOutcome.Finished
    }

    private suspend fun update(localId: String, change: (TranscriptionTask) -> TranscriptionTask): TranscriptionTask {
        val state = repository.updateDurably { current ->
            current.copy(transcriptionTasks = current.transcriptionTasks.map {
                if (it.localId != localId) return@map it
                val next = change(it)
                // Une annulation demandée n'est écrasée que par une fin de tâche.
                if (it.phase == TranscriptionPhase.CANCEL_REQUESTED && !next.phase.isTerminal())
                    next.copy(phase = TranscriptionPhase.CANCEL_REQUESTED) else next
            })
        }
        return state.transcriptionTasks.first { it.localId == localId }
    }

    private companion object {
        val MAYBE_SENT = setOf(TranscriptionPhase.SUBMITTING, TranscriptionPhase.UNKNOWN_SUBMISSION)
        const val CANNOT_CONFIRM = "L'arrêt côté serveur ne peut pas être confirmé : l'envoi n'a pas reçu d'identifiant."
    }
}
