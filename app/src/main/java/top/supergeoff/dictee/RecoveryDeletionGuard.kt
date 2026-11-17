package top.supergeoff.dictee

/** Admission et péremption des cibles de récupération face aux suppressions locales. */
class RecoveryDeletionGuard {
    private val generations = mutableMapOf<String, Long>()
    private var deleting = emptySet<String>()

    @Synchronized fun capture(names: Set<String>): Map<String, Long>? {
        if (names.any { it in deleting }) return null
        return names.associateWith { generations[it] ?: 0L }
    }

    @Synchronized fun isCurrent(token: Map<String, Long>, name: String): Boolean =
        name !in deleting && token[name] != null && token[name] == (generations[name] ?: 0L)

    suspend fun <T> withDeletion(names: Set<String>, block: suspend () -> T): T {
        synchronized(this) {
            check(deleting.isEmpty())
            deleting = names
            names.forEach { generations[it] = (generations[it] ?: 0L) + 1L }
        }
        try { return block() } finally { synchronized(this) { deleting = emptySet() } }
    }
}
