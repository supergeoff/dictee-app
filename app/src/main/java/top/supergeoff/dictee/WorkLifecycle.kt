package top.supergeoff.dictee

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Compte publié avant le démarrage du service et après la sortie de chaque travail. */
internal class ActiveWork {
    private val count = MutableStateFlow(0)
    val value: Int get() = count.value
    val changes: StateFlow<Int> get() = count
    fun begin() { count.update { it + 1 } }
    fun end() { count.update { it - 1 } }

    suspend fun <T> track(block: suspend () -> T): T {
        begin()
        try { return block() } finally { end() }
    }
}

internal suspend fun <T> observeServiceWork(ui: StateFlow<T>, jobs: ActiveWork, onUpdate: (T, Int) -> Unit) {
    combine(ui, jobs.changes) { _, _ -> Unit }.collect { onUpdate(ui.value, jobs.value) }
}

/** Garde le service actif pendant le suivi, sans retenir le verrou du coordinateur appelant. */
internal class InteractiveTranscriptionStarter(
    private val scope: CoroutineScope,
    private val jobs: ActiveWork,
    private val startService: () -> Unit,
    private val lifecycle: InteractiveTranscriptionLifecycle,
    private val canResume: () -> Boolean = { true },
    private val canLaunchAutomatically: () -> Boolean = { true },
) {
    suspend fun resume(repository: LocalStateRepository) {
        if (repository.awaitInitialized().transcriptionTasks.any { !it.phase.isTerminal() && !it.suspended } && canResume()) request()
    }

    fun requestAutomatic(): Job? = if (canLaunchAutomatically()) request() else null

    fun request(): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        jobs.track {
            startService()
            lifecycle.request().join()
        }
    }
}

/** Une demande arrivée pendant le suivi impose une nouvelle lecture, sans reprendre les tâches confiées au worker. */
internal class InteractiveTranscriptionLifecycle(
    private val scope: CoroutineScope,
    private val follow: suspend (MutableSet<String>) -> InteractiveRun,
) {
    private val lock = Any()
    private var generation = 0L
    private var loop: Job? = null

    fun request(): Job = synchronized(lock) {
        generation++
        loop?.takeIf { it.isActive } ?: scope.launch(start = CoroutineStart.LAZY) {
            val excluded = mutableSetOf<String>()
            val self = currentCoroutineContext()[Job]
            try {
                while (true) {
                    val observed = synchronized(lock) { generation }
                    follow(excluded)
                    val done = synchronized(lock) {
                        if (generation != observed) false else {
                            if (loop === self) loop = null
                            true
                        }
                    }
                    if (done) return@launch
                }
            } finally {
                synchronized(lock) { if (loop === self) loop = null }
            }
        }.also { loop = it; it.start() }
    }

    fun stop() {
        val current = synchronized(lock) { loop }
        current?.cancel()
    }
}
