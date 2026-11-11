package top.supergeoff.dictee

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Attend la reprise puis garantit une nouvelle lecture si l'application est au premier plan. */
internal suspend fun refreshAfterInitializationRetry(
    repository: LocalStateRepository,
    isForeground: () -> Boolean,
    coordinator: RefreshCoordinator,
    refresh: suspend () -> Unit,
) {
    repository.awaitInitialized()
    refreshWhenForeground(coordinator, isForeground, refresh)
}

/** Une demande de premier plan attend le créneau puis contrôle à nouveau le cycle de vie. */
internal suspend fun refreshWhenForeground(
    coordinator: RefreshCoordinator,
    isForeground: () -> Boolean,
    refresh: suspend () -> Unit,
) {
    if (isForeground()) coordinator.run(waitForCurrent = true) {
        if (isForeground()) refresh()
    }
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

/**
 * Le changement de cycle de vie est immédiat. Les lectures du boîtier et la fermeture
 * se sérialisent : une lecture engagée finit avant la fermeture, une lecture en attente
 * recontrôle le premier plan une fois le verrou obtenu.
 */
internal class ForegroundBoxGate {
    private val mutex = Mutex()
    @Volatile var isForeground: Boolean = false
        private set

    fun onForeground() { isForeground = true }
    fun onBackground() { isForeground = false }

    suspend fun <T> withForeground(block: suspend () -> T): T? = mutex.withLock {
        if (isForeground) block() else null
    }

    suspend fun closeIfBackground(close: suspend () -> Unit) = mutex.withLock {
        if (!isForeground) close()
    }
}

/** La fermeture attend la lecture Bluetooth, jamais la fin de sa sauvegarde. */
internal suspend fun readBoxListForForeground(
    gate: ForegroundBoxGate,
    repository: LocalStateRepository,
    shouldSkip: () -> Boolean,
    list: suspend (String) -> Result<List<ListItem.Entry>>,
): Boolean {
    repository.awaitInitialized()
    val (success, pendingSave) = gate.withForeground {
        val address = checkNotNull(repository.state.value).deviceAddress ?: return@withForeground null
        if (shouldSkip()) return@withForeground null
        val entries = list(address).getOrNull() ?: return@withForeground false to null
        // La mise en file non bloquante préserve l'ordre des lectures ; l'attente est hors du verrou.
        true to repository.update { if (it.deviceAddress == address) it.copy(boxList = entries) else it }
    } ?: return true
    pendingSave?.await()
    return success
}
