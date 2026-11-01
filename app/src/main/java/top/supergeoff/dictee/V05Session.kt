package top.supergeoff.dictee

import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime

/** Origine d'une notification : trames de commande, audio d'un fichier (B0B4) ou audio en direct (B0B3). */
enum class LinkChannel { COMMAND, FILE_AUDIO, RECORD_AUDIO }

/** Notification du boîtier. `source` est le repère d'UUID de la caractéristique, pour le journal. */
class Notification(val channel: LinkChannel, val bytes: ByteArray, val source: String)

/** Liaison Bluetooth vue par la session. Le canal `incoming` se ferme quand la liaison est coupée. */
interface Link {
    val incoming: ReceiveChannel<Notification>
    suspend fun write(frame: ByteArray)
    suspend fun close()
}

/** Erreur qui arrête la synchronisation. */
class SessionException(message: String) : Exception(message)

sealed class Download {
    abstract val name: String

    data class Ok(override val name: String, val file: File, val size: Long) : Download()

    /** `fatal` : la synchronisation s'arrête après cet échec. `refused` : le boîtier a refusé l'envoi avec `0x0b`. */
    data class Failed(
        override val name: String,
        val reason: String,
        val fatal: Boolean,
        val refused: Boolean = false,
    ) : Download()
}

fun ByteArray.hexString(): String = joinToString("") { "%02x".format(it) }

/** Séquence et transfert décrits dans docs/protocole-v05.md, écrits contre l'interface Link. */
class V05Session(
    private val link: Link,
    private val journal: (String) -> Unit,
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
    private val commandTimeoutMs: Long = 5_000,
    private val silenceTimeoutMs: Long = 20_000,
) {

    /** Appairage rejoué, numéro de série et informations pour le journal, mise à l'heure. */
    suspend fun handshake() {
        val id = command(Cmd.BIND_READ).payload
        if (id.isEmpty()) throw SessionException("identifiant d'appairage vide")
        journal("identifiant d'appairage : ${Payloads.bindIdText(id)}")
        if (Payloads.status(command(Cmd.BIND, Payloads.bindRequest(id)).payload) != 0) {
            throw SessionException("appairage refusé")
        }
        journal("numéro de série : ${String(command(Cmd.SERIAL).payload, Charsets.US_ASCII)}")
        command(Cmd.INFO)
        command(Cmd.TIME, Payloads.timeRequest(clock()))
    }

    /** La fonction envoie `0x18` avec 00, puis lit la liste des enregistrements jusqu'à la notification de fin. */
    suspend fun listRecords(): List<ListItem.Entry> {
        val status = Payloads.status(command(Cmd.BEFORE_LIST, byteArrayOf(0)).payload)
        if (status != 0) journal("0x18 : statut %02x".format(status))
        send(Cmd.LIST)
        val entries = mutableListOf<ListItem.Entry>()
        while (true) {
            val frame = awaitCommand(Cmd.LIST) ?: throw SessionException("liste incomplète : pas de fin de liste")
            when (val item = Payloads.listItem(frame.payload)) {
                is ListItem.Entry -> entries += item
                ListItem.End -> break
            }
        }
        journal("liste : ${entries.size} enregistrements")
        return entries
    }

    /**
     * Demande `name` avec `0x0b`, écrit l'audio de la caractéristique des fichiers dans `<nom>.mp3.part`,
     * contrôle la taille annoncée et l'en-tête MP3, puis renomme le fichier en `<nom>.mp3`.
     */
    suspend fun download(
        name: String,
        expectedSize: Long,
        dir: File,
        onProgress: (received: Long, expected: Long) -> Unit,
    ): Download {
        val part = File(dir, "$name.mp3.part")
        var announced: Long? = null
        var received = 0L
        var ignoredLive = 0L
        var failure: Download.Failed? = null
        val perSource = linkedMapOf<String, LongArray>()

        send(Cmd.FILE, Payloads.fileRequest(name, 0L))
        FileOutputStream(part).use { out ->
            loop@ while (true) {
                val timeout = if (announced == null) commandTimeoutMs else silenceTimeoutMs
                val n = try {
                    withTimeoutOrNull(timeout) { link.incoming.receive() }
                } catch (e: ClosedReceiveChannelException) {
                    failure = Download.Failed(name, "liaison Bluetooth coupée pendant le transfert", fatal = true)
                    break@loop
                }
                if (n == null) {
                    val reason = if (announced == null) "pas de réponse à 0x0b" else "aucun octet audio pendant ${silenceTimeoutMs / 1000} s"
                    failure = Download.Failed(name, reason, fatal = true)
                    break@loop
                }
                when (n.channel) {
                    LinkChannel.FILE_AUDIO -> {
                        out.write(n.bytes)
                        received += n.bytes.size
                        val s = perSource.getOrPut(n.source) { LongArray(2) }
                        s[0] += n.bytes.size.toLong()
                        s[1] += 1
                        onProgress(received, announced ?: expectedSize)
                    }
                    LinkChannel.RECORD_AUDIO -> ignoredLive += n.bytes.size
                    LinkChannel.COMMAND -> {
                        val frame = Frame.parse(n.bytes)
                        if (frame == null) {
                            journal("RX ${n.bytes.hexString()} (trame invalide, ignorée)")
                            continue@loop
                        }
                        journal("RX ${n.bytes.hexString()}")
                        when (frame.cmd) {
                            Cmd.FILE -> {
                                val status = Payloads.status(frame.payload)
                                if (announced == null) {
                                    val size = Payloads.fileSize(frame.payload)
                                    if (status != 0 || size == null) {
                                        // Un refus ne concerne que ce fichier : la file continue avec le suivant.
                                        val code = if (status < 0) "sans statut" else "statut %02x".format(status)
                                        failure = Download.Failed(name, "le boîtier refuse l'envoi (0x0b, $code)", fatal = false, refused = true)
                                        break@loop
                                    }
                                    announced = size
                                    journal("le boîtier envoie $name : $size octets")
                                } else if (status == Payloads.FILE_END) {
                                    break@loop
                                } else {
                                    journal("0x0b inattendu pendant le transfert, ignoré")
                                }
                            }
                            Cmd.NEW_RECORD -> journal("nouvel enregistrement signalé : ${Payloads.newRecordName(frame.payload)}")
                            else -> Unit
                        }
                    }
                }
            }
        }
        for ((source, s) in perSource) journal("audio reçu sur $source : ${s[0]} octets en ${s[1]} notifications")
        if (ignoredLive > 0) journal("audio en direct ignoré : $ignoredLive octets")

        failure?.let {
            part.delete()
            journal("échec de $name : ${it.reason}")
            return it
        }
        val size = announced ?: expectedSize
        if (received != size) {
            part.delete()
            journal("échec de $name : $received octets reçus, $size annoncés")
            return Download.Failed(name, "taille reçue $received au lieu de $size", fatal = false)
        }
        val head = part.inputStream().use { input -> ByteArray(2).also { input.read(it) } }
        if (!Payloads.isMp3Header(head)) {
            part.delete()
            journal("échec de $name : en-tête MP3 absent")
            return Download.Failed(name, "en-tête MP3 absent", fatal = false)
        }
        val target = File(dir, "$name.mp3")
        target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            return Download.Failed(name, "renommage impossible", fatal = false)
        }
        onProgress(received, size)
        journal("$name reçu : $received octets")
        return Download.Ok(name, target, received)
    }

    /** Transfère les enregistrements dans l'ordre donné. Un échec fatal arrête la file. Renvoie les résultats. */
    suspend fun downloadAll(
        entries: List<ListItem.Entry>,
        dir: File,
        onStart: (name: String, index: Int, total: Int) -> Unit,
        onProgress: (name: String, received: Long, expected: Long) -> Unit,
        onResult: (Download) -> Unit,
    ): List<Download> {
        val results = mutableListOf<Download>()
        for ((i, entry) in entries.withIndex()) {
            onStart(entry.name, i + 1, entries.size)
            val result = download(entry.name, entry.size, dir) { got, total -> onProgress(entry.name, got, total) }
            results += result
            onResult(result)
            if (result is Download.Failed && result.fatal) break
        }
        return results
    }

    suspend fun close() = link.close()

    private suspend fun send(cmd: Int, payload: ByteArray = ByteArray(0)) {
        val frame = Frame.build(cmd, payload)
        journal("TX ${frame.hexString()}")
        link.write(frame)
    }

    private suspend fun command(cmd: Int, payload: ByteArray = ByteArray(0)): DeviceFrame {
        send(cmd, payload)
        return awaitCommand(cmd) ?: throw SessionException("pas de réponse à 0x%02x".format(cmd))
    }

    /** Attend la trame `cmd` pendant le délai de commande. Les autres notifications sont notées et ignorées. */
    private suspend fun awaitCommand(cmd: Int): DeviceFrame? = try {
        withTimeoutOrNull(commandTimeoutMs) {
            var found: DeviceFrame? = null
            while (found == null) {
                val n = link.incoming.receive()
                if (n.channel != LinkChannel.COMMAND) {
                    journal("audio inattendu sur ${n.source} : ${n.bytes.size} octets ignorés")
                    continue
                }
                val frame = Frame.parse(n.bytes)
                if (frame == null) {
                    journal("RX ${n.bytes.hexString()} (trame invalide, ignorée)")
                    continue
                }
                journal("RX ${n.bytes.hexString()}")
                if (frame.cmd == cmd) found = frame
            }
            found
        }
    } catch (e: ClosedReceiveChannelException) {
        throw SessionException("liaison Bluetooth coupée")
    }
}
