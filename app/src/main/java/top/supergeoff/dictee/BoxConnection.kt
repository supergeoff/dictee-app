package top.supergeoff.dictee

import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** État de la liaison avec le boîtier. [NONE], aucun boîtier choisi, est réservé à l'interface. */
enum class LinkStatus { NONE, OFFLINE, CONNECTING, CONNECTED }

/**
 * Une seule liaison Bluetooth et une seule [V05Session] à la fois, derrière un [Mutex] : [list], [withSession] et [close] s'enchaînent.
 * La liaison s'ouvre au premier appel (connexion, puis poignée de main) et sert aux appels suivants tant qu'elle reste ouverte.
 * Une exception, celle de la liaison ou celle du bloc de [withSession], ferme la liaison et remonte.
 * Les lignes de la session et de la liaison vont au journal avec la source BLE.
 */
class BoxConnection(context: Context, private val log: Log) {
    /** Liaison ouverte avec sa session. [dropped] passe à vrai quand la liaison se coupe : la session ne sert plus. */
    private class Open(val address: String, val session: V05Session, val dropped: AtomicBoolean)

    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val lock = Any()
    private val statusFlow = MutableStateFlow(LinkStatus.OFFLINE)
    private val journal: (String) -> Unit = { log.add(Source.BLE, it) }

    /** Liaison ouverte, ou coupée et pas encore fermée. Lue et modifiée sous [lock], car la coupure arrive du fil du GATT. */
    private var current: Open? = null

    /** OFFLINE au départ. */
    val status: StateFlow<LinkStatus> = statusFlow.asStateFlow()

    /** Liste des enregistrements du boîtier. Ouvre la liaison si elle est fermée. */
    suspend fun list(address: String): List<ListItem.Entry> = withSession(address) { it.listRecords() }

    /** Exécute [block] avec la session du boîtier d'adresse [address]. Ouvre la liaison si elle est fermée, ou si elle va vers un autre boîtier. */
    suspend fun <T> withSession(address: String, block: suspend (V05Session) -> T): T = mutex.withLock {
        try {
            block(open(address))
        } catch (e: Exception) {
            if (e !is CancellationException) log.error(Source.BLE, e.message ?: e.javaClass.simpleName)
            withContext(NonCancellable) { closeCurrent() }
            throw e
        }
    }

    /** Ferme la liaison et passe à OFFLINE. */
    suspend fun close() = mutex.withLock { closeCurrent() }

    /** Session de la liaison ouverte vers [address], sinon une nouvelle liaison : connexion, puis poignée de main. */
    private suspend fun open(address: String): V05Session {
        synchronized(lock) { current?.takeIf { it.address == address && !it.dropped.get() } }?.let { return it.session }
        closeCurrent()
        statusFlow.value = LinkStatus.CONNECTING
        val dropped = AtomicBoolean(false)
        val link = BleLink.connect(appContext, address, journal) { onLinkClosed(dropped) }
        val session = V05Session(link, journal)
        synchronized(lock) { current = Open(address, session, dropped) }
        session.handshake()
        synchronized(lock) {
            if (dropped.get()) throw SessionException("liaison Bluetooth coupée")
            statusFlow.value = LinkStatus.CONNECTED
        }
        return session
    }

    /** Appelé depuis le fil du GATT quand la liaison se coupe ou se ferme : l'état passe à OFFLINE si c'est la liaison courante. */
    private fun onLinkClosed(dropped: AtomicBoolean) {
        synchronized(lock) {
            dropped.set(true)
            if (current?.dropped === dropped) statusFlow.value = LinkStatus.OFFLINE
        }
    }

    /** Oublie la liaison courante et ferme son GATT, même coupé, puis passe à OFFLINE. */
    private suspend fun closeCurrent() {
        val old = synchronized(lock) {
            val previous = current
            current = null
            statusFlow.value = LinkStatus.OFFLINE
            previous
        }
        try {
            old?.session?.close()
        } catch (e: Exception) {
            log.error(Source.BLE, "fermeture de la liaison : ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
