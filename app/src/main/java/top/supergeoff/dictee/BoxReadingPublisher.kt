package top.supergeoff.dictee

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Publie uniquement les lectures sauvegardées de la liaison encore courante. */
internal class BoxReadingPublisher(
    private val save: suspend (BoxReading) -> Boolean,
) {
    private val lock = Any()
    private var generation = 0L
    private val fresh = MutableStateFlow<String?>(null)
    val freshAddress: StateFlow<String?> = fresh.asStateFlow()

    fun open(): Long = synchronized(lock) {
        generation++
        fresh.value = null
        generation
    }

    fun close(token: Long) = synchronized(lock) {
        if (generation == token) {
            generation++
            fresh.value = null
        }
    }

    suspend fun publish(token: Long, reading: BoxReading) {
        synchronized(lock) {
            check(generation == token) { "liaison Bluetooth remplacée" }
            fresh.value = null
        }
        val saved = save(reading)
        synchronized(lock) {
            check(generation == token && saved) { "lecture du boîtier non publiée" }
            fresh.value = reading.address
        }
    }
}
