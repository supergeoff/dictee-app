package top.supergeoff.dictee

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Étape persistée d'une demande déclenchée par une récupération explicite. */
enum class AutomaticProcessingPhase { RECOVERING, READY, WAITING_SETTINGS }

/** Intention de traitement attachée à une récupération et à son dossier local. */
data class AutomaticProcessingRequest(
    val id: String,
    val recordingName: String,
    val folderUri: String,
    val phase: AutomaticProcessingPhase,
    val reason: String? = null,
)

/** Reprend chaque intention validée indépendamment des autres. */
class AutomaticProcessingCoordinator(private val repository: LocalStateRepository, private val transcription: TranscriptionCoordinator) {
    private val mutex = Mutex()

    suspend fun reconcile(): Unit = mutex.withLock {
        val requests = repository.awaitInitialized().automaticProcessingRequests.filter { it.phase != AutomaticProcessingPhase.RECOVERING }
        for (request in requests) {
            try { transcription.startAutomatic(request.id) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* L'intention ou la tâche persistée reste récupérable. */ }
        }
    }
}
