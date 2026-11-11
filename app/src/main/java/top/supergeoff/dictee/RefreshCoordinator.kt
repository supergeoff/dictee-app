package top.supergeoff.dictee

import kotlinx.coroutines.CompletableDeferred

/** Attend la reprise puis garantit une nouvelle lecture si l'application est au premier plan. */
internal suspend fun refreshAfterInitializationRetry(
    repository: LocalStateRepository,
    isForeground: () -> Boolean,
    coordinator: RefreshCoordinator,
    refresh: suspend () -> Unit,
) {
    repository.awaitInitialized()
    if (isForeground()) coordinator.run(waitForCurrent = true, block = refresh)
}

/** Coordonne le rafraîchissement et l'association, sans perdre la demande après une reprise. */
internal class RefreshCoordinator(private val onRefreshing: (Boolean) -> Unit) {
    private val lock = Any()
    private var current: CompletableDeferred<Unit>? = null

    fun tryStart(): Boolean = synchronized(lock) {
        if (current != null) return@synchronized false
        current = CompletableDeferred()
        onRefreshing(true)
        true
    }

    fun finish() = synchronized(lock) {
        val completed = checkNotNull(current)
        current = null
        onRefreshing(false)
        completed.complete(Unit)
        Unit
    }

    suspend fun run(waitForCurrent: Boolean, block: suspend () -> Unit) {
        while (true) {
            val previous = synchronized(lock) {
                if (current == null) {
                    current = CompletableDeferred()
                    onRefreshing(true)
                    null
                } else current
            }
            if (previous == null) break
            if (!waitForCurrent) return
            previous.await()
        }
        try { block() } finally { finish() }
    }
}
