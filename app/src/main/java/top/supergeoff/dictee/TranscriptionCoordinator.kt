package top.supergeoff.dictee

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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
    /** Prépare le VTT et confie sa promotion synchrone à la garde ; protège l'ancien fichier, ou lève [PhoneStoreException]. */
    private val saveVtt: suspend (TranscriptionTask, ByteArray, ((() -> Unit) -> Unit)) -> Unit = { _, _, _ -> error("Sauvegarde indisponible") },
    private val launch: suspend () -> Unit = {},
    private val synthesisClient: SynthesisClient? = null,
    private val synthesisStorage: SynthesisStorage? = null,
    private val phoneStore: (String) -> PhoneStore = { error("Dossier indisponible") },
    private val synthesisCredentialAccessible: (String) -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
    private val promotionGuard: ReentrantLock = ReentrantLock(),
    private val prepareRequested: suspend (Set<String>?, RequestedWork, Set<String>) -> List<TranscriptionTask> = { names, _, _ -> prepare(names) },
) {
    private val mutex = Mutex()
    // Accès protégé par promotionGuard : l'enregistrement ne peut manquer une annulation durable.
    private val synthesisCalls = mutableMapOf<String, Deferred<ByteArray>>()

    /** Rétablit la programmation durable sans effectuer de requête au serveur. */
    suspend fun reconcile() = mutex.withLock {
        val all = repository.awaitInitialized().transcriptionTasks
        withContext(Dispatchers.IO) { promotionGuard.withLock {
            all.filter { it.phase.isTerminal() && it.synthesis != null }.forEach {
                try { synthesisStorage?.discard(it.localId) } catch (_: java.io.IOException) { }
            }
        } }
        for (task in all.filterNot { it.phase.isTerminal() }) schedule(task.localId)
    }

    suspend fun start(names: Set<String>?, request: RequestedWork = RequestedWork.MISSING): List<String> = mutex.withLock {
        recoverySafety.withTransferLock {
            val existing = repository.awaitInitialized().transcriptionTasks
            val active = existing.filterNot { it.phase.isTerminal() }.map { it.recordingName }.toSet()
            val tasks = prepareRequested(names, request, active).filter { it.recordingName !in active }.sortedBy { it.recordingName }
            for (task in tasks) schedule(task.localId)
            if (tasks.isNotEmpty()) {
                repository.updateDurably { it.copy(transcriptionTasks = it.transcriptionTasks + tasks) }
                launch()
            }
            tasks.map { it.localId }
        }
    }

    /**
     * L'intention d'annuler est persistée sous la garde de promotion, avant tout appel réseau et sans attendre le mutex
     * de la file : une soumission longue ne la retarde pas, et [update] la préserve face aux réponses qui arrivent ensuite.
     */
    suspend fun requestCancel(localId: String) {
        val call = withContext(Dispatchers.IO) { promotionGuard.withLock {
        runBlocking(Dispatchers.IO) { repository.update { current ->
            current.copy(transcriptionTasks = current.transcriptionTasks.map { task ->
                when {
                    task.localId != localId || task.phase.isTerminal() -> task
                    task.phase == TranscriptionPhase.SYNTHESIZING -> task.copy(
                        phase = TranscriptionPhase.CANCELLED, suspended = false,
                        synthesis = task.synthesis?.copy(phase = SynthesisPhase.CANCELLED, suspensionReason = "L'arrêt côté serveur et la facturation ne peuvent pas être garantis."))
                    isLocalTranscriptionAbandon(task) -> task.copy(
                        phase = TranscriptionPhase.CANCELLED, suspended = false, suspensionReason = LOCAL_ABANDON_WARNING,
                    )
                    else -> task.copy(
                        phase = TranscriptionPhase.CANCEL_REQUESTED, suspended = false, suspensionReason = null,
                        error = if (task.serverId == null && task.phase in MAYBE_SENT) CANNOT_CONFIRM else task.error,
                    )
                }
            })
        }.await().getOrThrow() }
        val task = runBlocking(Dispatchers.IO) { repository.awaitInitialized().transcriptionTasks.firstOrNull { it.localId == localId } }
        if (task?.phase == TranscriptionPhase.CANCELLED && task.synthesis?.phase == SynthesisPhase.CANCELLED) {
            try { synthesisStorage?.discard(localId) } catch (_: java.io.IOException) { }
            synthesisCalls[localId]
        } else null
        } }
        call?.cancel()
        mutex.withLock { recoverySafety.withTransferLock { runLocked(localId) } }
    }

    suspend fun retrySynthesis(localId: String) = mutex.withLock {
        val task = repository.awaitInitialized().transcriptionTasks.firstOrNull { it.localId == localId } ?: return@withLock
        val work = task.synthesis ?: return@withLock
        if (task.phase != TranscriptionPhase.SYNTHESIZING || work.phase != SynthesisPhase.SUSPENDED || work.context == null ||
            (work.stagedSha256 == null && (work.context.vttSha256.isNullOrBlank() || work.context.systemPrompt.isNullOrBlank()))) return@withLock
        val recovered = try {
            withContext(Dispatchers.IO) { promotionGuard.withLock {
                work.stagedSha256 ?: if (work.attempts > 0) synthesisStorage?.stagedSha256(localId) else null
            } }
        } catch (_: java.io.IOException) { return@withLock }
        schedule(localId)
        update(localId) { it.copy(suspended = false, suspensionReason = null, synthesis = work.copy(
            phase = if (recovered != null) SynthesisPhase.SAVE_PENDING else SynthesisPhase.READY,
            stagedSha256 = recovered, attempts = 0, nextAttemptAtMillis = null, suspensionReason = null)) }
        launch()
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
        if (task.phase == TranscriptionPhase.SYNTHESIZING) return synthesize(task)

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
            // En reprise serveur seulement, un VTT égal peut être celui déjà promu avant la panne.
            // Les tâches DIC-4 sans synthèse et avec remplacement gardent leur parcours d'origine.
            val alreadySaved = known == null && (task.synthesis != null || !task.replaceVtt) && try {
                withContext(Dispatchers.IO) {
                    val phone = phoneStore(task.folderUri)
                    phone.list().any { it.fileName == task.vttName } &&
                        TranscriptionAudio.sha256(phone, task.vttName) == TranscriptionAudio.sha256(bytes)
                }
            } catch (e: java.io.IOException) {
                throw PhoneStoreException("Le VTT ou le dossier est inaccessible. Vérifie le dossier avant de relancer.", e)
            }
            if (!alreadySaved) {
                var promoted = false
                saveVtt(task, bytes) { promote -> promotionGuard.withLock {
                    val current = runBlocking(Dispatchers.IO) { repository.awaitInitialized().transcriptionTasks.first { it.localId == localId } }
                    if (current.phase == TranscriptionPhase.SAVING) {
                        promote()
                        promoted = true
                    }
                } }
                if (!promoted) return RunOutcome.Resume
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PhoneStoreException) {
            val current = update(localId) {
                if (it.phase != TranscriptionPhase.SAVING) it else it.copy(
                    suspended = true, suspensionReason = e.message ?: "Écriture impossible dans le dossier.")
            }
            return if (current.phase == TranscriptionPhase.SAVING) RunOutcome.Suspended else RunOutcome.Resume
        } catch (_: java.io.IOException) {
            return RunOutcome.Resume
        }
        val work = task.synthesis
        val resolved = work?.context?.let { context ->
            val speakers = WebVtt.parse(bytes).mapNotNull { it.speaker }.distinct().joinToString(", ").ifEmpty { "Non disponible" }
            val metadata = context.metadata
            context.copy(vttSha256 = sha256(bytes), systemPrompt = SynthesisPrompt.render(context.promptTemplate,
                PromptVariables(metadata.type, metadata.date, metadata.duree, speakers)))
        }
        val saved = update(localId) { current ->
            when {
                current.phase != TranscriptionPhase.SAVING -> current
                current.synthesis == null -> current.copy(phase = TranscriptionPhase.COMPLETED, progress = 1.0, error = null)
                resolved == null -> current.copy(phase = TranscriptionPhase.COMPLETED, progress = 1.0, error = null,
                    synthesis = current.synthesis.copy(phase = SynthesisPhase.SUSPENDED,
                        suspensionReason = "Configure la synthèse puis crée explicitement un travail MD."))
                else -> current.copy(phase = TranscriptionPhase.SYNTHESIZING, progress = 1.0, error = null,
                    synthesis = current.synthesis.copy(phase = SynthesisPhase.READY, context = resolved))
            }
        }
        return if (saved.phase == TranscriptionPhase.COMPLETED) RunOutcome.Finished else RunOutcome.Resume
    }

    private suspend fun synthesize(task: TranscriptionTask): RunOutcome {
        var work = task.synthesis ?: return RunOutcome.Suspended
        if (work.context == null) {
            update(task.localId) { it.copy(phase = TranscriptionPhase.COMPLETED, synthesis = work.copy(
                phase = SynthesisPhase.SUSPENDED, suspensionReason = "Configure la synthèse puis crée explicitement un travail MD.")) }
            return RunOutcome.Finished
        }
        if (work.phase == SynthesisPhase.SUSPENDED) return RunOutcome.Suspended
        if (work.phase == SynthesisPhase.SAVE_PENDING) return saveSynthesis(task)
        if (work.stagedSha256 != null) return suspendSynthesis(task.localId, "État de synthèse incohérent. Crée une nouvelle synthèse.")
        if (work.phase == SynthesisPhase.CALLING) {
            val recovered = try { withContext(Dispatchers.IO) { promotionGuard.withLock {
                synthesisStorage?.stagedSha256(task.localId)
            } } } catch (_: java.io.IOException) {
                return suspendSynthesis(task.localId, "Synthèse temporaire inaccessible.")
            }
            if (recovered != null) {
                val saved = update(task.localId) { current -> current.copy(synthesis = current.synthesis?.copy(
                    phase = SynthesisPhase.SAVE_PENDING, stagedSha256 = recovered, nextAttemptAtMillis = null)) }
                return saveSynthesis(saved)
            }
            if (work.attempts >= 3) return suspendSynthesis(task.localId, "Trois tentatives ont échoué. Relance manuellement la synthèse.")
            update(task.localId) { current -> current.copy(synthesis = current.synthesis?.copy(
                phase = SynthesisPhase.READY, nextAttemptAtMillis = clock() + if (work.attempts == 1) 60_000 else 120_000)) }
            return RunOutcome.Resume
        }
        if (work.phase == SynthesisPhase.WAITING_VTT && work.attempts != 0)
            return suspendSynthesis(task.localId, "État de synthèse incohérent. Crée une nouvelle synthèse.")
        if (work.nextAttemptAtMillis != null && clock() < work.nextAttemptAtMillis) return RunOutcome.Resume
        val phone = phoneStore(task.folderUri)
        val bytes = try { withContext(Dispatchers.IO) { phone.open(task.vttName).use { it.readBytes() } } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { return suspendSynthesis(task.localId, "Le VTT ou le dossier est inaccessible.") }
        val hash = sha256(bytes)
        var context = work.context
        if (work.phase == SynthesisPhase.WAITING_VTT) {
            val speakers = try { WebVtt.parse(bytes).mapNotNull { it.speaker }.distinct().joinToString(", ").ifEmpty { "Non disponible" } }
                catch (_: Exception) { return suspendSynthesis(task.localId, "Le VTT est invalide.") }
            val metadata = context.metadata
            context = context.copy(vttSha256 = hash, systemPrompt = SynthesisPrompt.render(context.promptTemplate,
                PromptVariables(metadata.type, metadata.date, metadata.duree, speakers)))
            work = work.copy(context = context, phase = SynthesisPhase.READY)
            update(task.localId) { it.copy(synthesis = work) }
        }
        if (context.vttSha256 != hash || context.systemPrompt.isNullOrBlank())
            return suspendSynthesis(task.localId, "Le VTT a changé ou le contexte est invalide. Crée une nouvelle synthèse.")
        val accessible = try { withContext(Dispatchers.IO) { synthesisCredentialAccessible(context.credentialRef) } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        if (!accessible) return suspendSynthesis(task.localId, "Clé de synthèse inaccessible. Vérifie les réglages.")
        val attempt = work.attempts + 1
        if (attempt > 3) return suspendSynthesis(task.localId, "Trois tentatives ont échoué. Relance manuellement la synthèse.")
        val calling = update(task.localId) { current ->
            if (current.phase != TranscriptionPhase.SYNTHESIZING) current else current.copy(synthesis = work.copy(
                phase = SynthesisPhase.CALLING, attempts = attempt,
                nextAttemptAtMillis = clock() + if (attempt == 1) 60_000 else 120_000))
        }
        if (calling.phase != TranscriptionPhase.SYNTHESIZING) return RunOutcome.Finished
        val result = try {
            supervisorScope {
                val call = async(start = CoroutineStart.LAZY) { checkNotNull(synthesisClient).complete(context, bytes) }
                val registered = withContext(Dispatchers.IO) { promotionGuard.withLock {
                    val current = runBlocking(Dispatchers.IO) { repository.awaitInitialized().transcriptionTasks.first { it.localId == task.localId } }
                    if (current.phase != TranscriptionPhase.SYNTHESIZING) false else {
                        synthesisCalls[task.localId] = call
                        true
                    }
                } }
                if (!registered) { call.cancel(); return@supervisorScope null }
                try { call.await() } finally {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { promotionGuard.withLock {
                        if (synthesisCalls[task.localId] === call) synthesisCalls.remove(task.localId)
                    } }
                }
            } ?: return RunOutcome.Finished
        }
        catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (repository.awaitInitialized().transcriptionTasks.first { it.localId == task.localId }.phase == TranscriptionPhase.CANCELLED)
                return RunOutcome.Finished
            throw e
        }
        catch (e: SynthesisClientException) {
            if (e.kind == SynthesisFailureKind.CREDENTIAL) {
                update(task.localId) { current -> current.copy(synthesis = current.synthesis?.copy(attempts = (attempt - 1).coerceAtLeast(0))) }
            }
            if (e.kind != SynthesisFailureKind.TRANSIENT || attempt >= 3)
                return suspendSynthesis(task.localId, e.message ?: "Synthèse impossible.")
            update(task.localId) { current -> current.copy(synthesis = current.synthesis?.copy(phase = SynthesisPhase.READY,
                nextAttemptAtMillis = clock() + if (attempt == 1) 60_000 else 120_000)) }
            return RunOutcome.Resume
        } catch (_: Exception) {
            if (attempt >= 3) return suspendSynthesis(task.localId, "Trois tentatives ont échoué. Relance manuellement la synthèse.")
            update(task.localId) { current -> current.copy(synthesis = current.synthesis?.copy(phase = SynthesisPhase.READY,
                nextAttemptAtMillis = clock() + if (attempt == 1) 60_000 else 120_000)) }
            return RunOutcome.Resume
        }
        val staged = try { withContext(Dispatchers.IO) { promotionGuard.withLock {
            val current = runBlocking(Dispatchers.IO) { repository.awaitInitialized().transcriptionTasks.first { it.localId == task.localId } }
            if (current.phase != TranscriptionPhase.SYNTHESIZING) return@withLock false
            val digest = checkNotNull(synthesisStorage).stage(task.localId, result)
            runBlocking(Dispatchers.IO) { repository.update { state -> state.copy(transcriptionTasks = state.transcriptionTasks.map {
                if (it.localId == task.localId && it.phase == TranscriptionPhase.SYNTHESIZING) it.copy(synthesis = it.synthesis?.copy(
                    phase = SynthesisPhase.SAVE_PENDING, stagedSha256 = digest, nextAttemptAtMillis = null)) else it
            }) }.await().getOrThrow() }
            true
        } } } catch (e: CancellationException) { throw e }
          catch (_: Exception) { return suspendSynthesis(task.localId, "Impossible d'enregistrer la synthèse temporaire.") }
        if (!staged) return RunOutcome.Finished
        return saveSynthesis(repository.awaitInitialized().transcriptionTasks.first { it.localId == task.localId })
    }

    private suspend fun suspendSynthesis(localId: String, reason: String): RunOutcome {
        update(localId) { current -> if (current.phase != TranscriptionPhase.SYNTHESIZING) current else current.copy(
            suspended = true, suspensionReason = reason, synthesis = current.synthesis?.copy(phase = SynthesisPhase.SUSPENDED, suspensionReason = reason)) }
        return RunOutcome.Suspended
    }

    private suspend fun saveSynthesis(task: TranscriptionTask): RunOutcome {
        val work = task.synthesis ?: return RunOutcome.Suspended
        val context = work.context ?: return suspendSynthesis(task.localId, "Contexte de synthèse absent.")
        val expected = work.stagedSha256 ?: return suspendSynthesis(task.localId, "Synthèse temporaire absente.")
        val phone = phoneStore(task.folderUri)
        try {
            withContext(Dispatchers.IO) {
                if (TranscriptionAudio.sha256(phone, task.vttName) != context.vttSha256) throw PhoneStoreException("Le VTT a changé.")
                val finalExists = phone.list().any { it.fileName == work.mdName }
                val finalHash = if (finalExists) TranscriptionAudio.sha256(phone, work.mdName) else null
                var promotionObserved = false
                if (finalHash != expected) {
                    val staged = promotionGuard.withLock { checkNotNull(synthesisStorage).staged(task.localId, expected) }
                    phone.writeGuarded(work.mdName, staged, work.replaceMd) { promote -> promotionGuard.withLock {
                        val current = runBlocking(Dispatchers.IO) { repository.awaitInitialized().transcriptionTasks.first { it.localId == task.localId } }
                        if (current.phase != TranscriptionPhase.SYNTHESIZING || current.synthesis?.phase != SynthesisPhase.SAVE_PENDING)
                            return@withLock
                        if (TranscriptionAudio.sha256(phone, task.vttName) != context.vttSha256) throw PhoneStoreException("Le VTT a changé. Crée une nouvelle synthèse.")
                        val exists = phone.list().any { it.fileName == work.mdName }
                        if (exists && !work.replaceMd) throw PhoneStoreException("Le MD existe déjà.")
                        val previous = if (exists) TranscriptionAudio.sha256(phone, work.mdName) else null
                        if (previous == expected) throw PhoneStoreException("La provenance du MD est inconnue.")
                        if (current.synthesis.promotionStarted && previous != current.synthesis.previousMdSha256)
                            throw PhoneStoreException("Le MD a changé pendant la reprise.")
                        if (!current.synthesis.promotionStarted) runBlocking(Dispatchers.IO) {
                            repository.update { state -> state.copy(transcriptionTasks = state.transcriptionTasks.map {
                                if (it.localId == task.localId && it.phase == TranscriptionPhase.SYNTHESIZING)
                                    it.copy(synthesis = it.synthesis?.copy(promotionStarted = true, previousMdSha256 = previous)) else it
                            }) }.await().getOrThrow()
                        }
                        promote()
                        promotionObserved = true
                    } }
                }
                promotionGuard.withLock {
                    if (TranscriptionAudio.sha256(phone, task.vttName) != context.vttSha256 ||
                        TranscriptionAudio.sha256(phone, work.mdName) != expected) throw PhoneStoreException("Les fichiers ont changé.")
                    runBlocking(Dispatchers.IO) { repository.update { state ->
                        val current = state.transcriptionTasks.first { it.localId == task.localId }
                        if (current.phase != TranscriptionPhase.SYNTHESIZING || current.synthesis?.phase != SynthesisPhase.SAVE_PENDING) state else {
                            val previous = state.synthesisProvenance.filterNot { it.folderUri == task.folderUri && it.mdName == work.mdName }
                            val provenance = if (promotionObserved) previous + SynthesisProvenance(
                                task.folderUri, work.mdName, context.model, checkNotNull(context.vttSha256), expected) else previous
                            state.copy(transcriptionTasks = state.transcriptionTasks.map { if (it.localId == task.localId) it.copy(
                                phase = TranscriptionPhase.COMPLETED, synthesis = it.synthesis?.copy(phase = SynthesisPhase.COMPLETED)) else it },
                                synthesisProvenance = provenance)
                        }
                    }.await().getOrThrow() }
                    checkNotNull(synthesisStorage).discard(task.localId)
                }
            }
        } catch (e: CancellationException) { throw e }
          catch (_: Exception) { return suspendSynthesis(task.localId, "Écriture de la synthèse impossible ou fichier modifié. Vérifie le dossier.") }
        return RunOutcome.Finished
    }

    private suspend fun update(localId: String, change: (TranscriptionTask) -> TranscriptionTask): TranscriptionTask {
        val state = repository.updateDurably { current ->
            current.copy(transcriptionTasks = current.transcriptionTasks.map {
                if (it.localId != localId) return@map it
                val next = change(it)
                // Une annulation persistée ne doit pas être écrasée par une réponse tardive.
                when {
                    it.phase == TranscriptionPhase.CANCELLED -> it
                    it.phase == TranscriptionPhase.CANCEL_REQUESTED && !next.phase.isTerminal() -> next.copy(phase = TranscriptionPhase.CANCEL_REQUESTED)
                    else -> next
                }
            })
        }
        return state.transcriptionTasks.first { it.localId == localId }
    }

    private companion object {
        fun sha256(bytes: ByteArray): String = TranscriptionAudio.sha256(bytes)
        val MAYBE_SENT = setOf(TranscriptionPhase.SUBMITTING, TranscriptionPhase.UNKNOWN_SUBMISSION)
        const val CANNOT_CONFIRM = "L'arrêt côté serveur ne peut pas être confirmé : l'envoi n'a pas reçu d'identifiant."
    }
}
