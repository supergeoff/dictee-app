package top.supergeoff.dictee

/** Admission et péremption des cibles de récupération face aux suppressions locales. */
class RecoveryDeletionGuard {
    private val generations = mutableMapOf<String, Long>()
    private var deleting = emptySet<String>()
    private val sending = mutableMapOf<String, Int>()
    private val processing = mutableMapOf<String, Int>()
    private var processingAll = 0

    @Synchronized fun admitProcessing(names: Set<String>?): AutoCloseable? {
        if (if (names == null) deleting.isNotEmpty() else names.any { it in deleting }) return null
        val admitted = names?.toSet()
        if (admitted == null) processingAll++ else admitted.forEach { processing[it] = (processing[it] ?: 0) + 1 }
        var closed = false
        return AutoCloseable { synchronized(this) {
            if (!closed) {
                closed = true
                if (admitted == null) processingAll-- else admitted.forEach { name ->
                    val count = processing.getValue(name) - 1
                    if (count == 0) processing.remove(name) else processing[name] = count
                }
            }
        } }
    }

    @Synchronized fun admitSend(names: Set<String>): AutoCloseable? {
        if (names.any { it in deleting }) return null
        val admitted = names.toSet()
        admitted.forEach { sending[it] = (sending[it] ?: 0) + 1 }
        var closed = false
        return AutoCloseable { synchronized(this) {
            if (!closed) {
                closed = true
                admitted.forEach { name ->
                    val count = sending.getValue(name) - 1
                    if (count == 0) sending.remove(name) else sending[name] = count
                }
            }
        } }
    }

    @Synchronized fun capture(names: Set<String>): Map<String, Long>? {
        if (names.any { it in deleting }) return null
        return names.associateWith { generations[it] ?: 0L }
    }

    @Synchronized fun isCurrent(token: Map<String, Long>, name: String): Boolean =
        name !in deleting && token[name] != null && token[name] == (generations[name] ?: 0L)

    suspend fun <T> withDeletion(names: Set<String>, block: suspend () -> T): T =
        withDeletionSnapshot(names) { block() }

    suspend fun <T> withDeletionSnapshot(names: Set<String>, block: suspend (Set<String>) -> T): T {
        val busy = synchronized(this) {
            check(deleting.isEmpty())
            deleting = names
            names.forEach { generations[it] = (generations[it] ?: 0L) + 1L }
            (names intersect (sending.keys + processing.keys)) + if (processingAll > 0) names else emptySet()
        }
        try { return block(busy) } finally { synchronized(this) { deleting = emptySet() } }
    }
}
