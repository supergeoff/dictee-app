package top.supergeoff.dictee

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class BoxReadingPublication(val reading: BoxReading)

/** Publie uniquement les lectures sauvegardées de la liaison encore courante. */
internal class BoxReadingPublisher(
    private val save: suspend (BoxReading) -> Boolean,
) {
    private val lock = Any()
    private var generation = 0L
    private val fresh = MutableStateFlow<BoxReadingPublication?>(null)
    val publication: StateFlow<BoxReadingPublication?> = fresh.asStateFlow()

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

    suspend fun publish(token: Long, reading: BoxReading): BoxReadingPublication {
        synchronized(lock) {
            check(generation == token) { "liaison Bluetooth remplacée" }
            fresh.value = null
        }
        val saved = save(reading)
        return synchronized(lock) {
            check(generation == token && saved) { "lecture du boîtier non publiée" }
            BoxReadingPublication(reading).also { fresh.value = it }
        }
    }
}
