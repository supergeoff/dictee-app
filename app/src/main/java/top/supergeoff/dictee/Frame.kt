package top.supergeoff.dictee

import java.time.LocalDateTime

/** Codes de commande du protocole A0 0A 01 du MECHEN V05. */
object Cmd {
    const val SERIAL = 0x01
    const val BIND_READ = 0x02
    const val BIND = 0x03
    const val TIME = 0x04
    const val INFO = 0x05
    const val NEW_RECORD = 0x06
    const val LIVE_END = 0x07
    const val LIST = 0x0a
    const val LIVE_START = 0x15

    /** L'application demande un enregistrement en mémoire avec cette commande : le boîtier répond par le statut et la taille, envoie l'audio sur la caractéristique des fichiers, puis termine par une trame 02. */
    const val FILE = 0x0b

    /** L'application envoie cette commande avec 00 avant chaque lecture de la liste, et le boîtier répond 00 en gardant le Bluetooth ouvert. */
    const val BEFORE_LIST = 0x18
}

/** Trame reçue du boîtier, après contrôle de l'en-tête, de la longueur et du CRC. */
class DeviceFrame(val cmd: Int, val payload: ByteArray)

object Frame {
    private const val H0 = 0xA0
    private const val H1 = 0x0A
    private const val H2 = 0x01

    /** CRC-16/ARC : polynôme 0x8005 réfléchi (0xA001), initialisation et xorout à zéro. */
    fun crc16(data: ByteArray, end: Int = data.size): Int {
        var crc = 0
        for (i in 0 until end) {
            crc = crc xor (data[i].toInt() and 0xff)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xffff
    }

    /** Trame envoyée par l'application : le CRC s'écrit en big-endian. */
    fun build(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= 0xff) { "payload trop long : ${payload.size}" }
        val out = ByteArray(5 + payload.size + 2)
        out[0] = H0.toByte()
        out[1] = H1.toByte()
        out[2] = H2.toByte()
        out[3] = cmd.toByte()
        out[4] = payload.size.toByte()
        payload.copyInto(out, 5)
        val crc = crc16(out, out.size - 2)
        out[out.size - 2] = (crc ushr 8).toByte()
        out[out.size - 1] = crc.toByte()
        return out
    }

    /** Trame envoyée par le boîtier : le CRC s'écrit en little-endian. Renvoie null si la trame est invalide. */
    fun parse(bytes: ByteArray): DeviceFrame? {
        if (bytes.size < 7) return null
        if ((bytes[0].toInt() and 0xff) != H0 || (bytes[1].toInt() and 0xff) != H1 || (bytes[2].toInt() and 0xff) != H2) return null
        val len = bytes[4].toInt() and 0xff
        if (bytes.size != 5 + len + 2) return null
        val crc = crc16(bytes, bytes.size - 2)
        val written = (bytes[bytes.size - 2].toInt() and 0xff) or ((bytes[bytes.size - 1].toInt() and 0xff) shl 8)
        if (crc != written) return null
        return DeviceFrame(bytes[3].toInt() and 0xff, bytes.copyOfRange(5, 5 + len))
    }
}

sealed class ListItem {
    /** Entrée de liste. [flag] : octet 19 du payload (0x01 pour une note, 0x00 pour un appel), -1 s'il manque. */
    data class Entry(val name: String, val size: Long, val flag: Int = -1) : ListItem()
    data object End : ListItem()
}

data class LiveStart(val status: Int, val name: String, val size: Long)
data class LiveEnd(val status: Int, val name: String, val size: Long)

/** Lecture et construction des payloads. Les entiers sont en big-endian, les noms font 14 caractères ASCII. */
object Payloads {
    const val NAME_LENGTH = 14

    fun status(payload: ByteArray): Int = if (payload.isEmpty()) -1 else payload[0].toInt() and 0xff

    fun bindIdText(payload: ByteArray): String = ascii(payload, 0, payload.size)

    /** Payload de 0x03 : l'identifiant reçu avec 0x02, suivi d'un octet nul. */
    fun bindRequest(bindReadPayload: ByteArray): ByteArray = bindReadPayload + byteArrayOf(0)

    fun timeRequest(t: LocalDateTime): ByteArray = byteArrayOf(
        (t.year - 2000).toByte(),
        t.monthValue.toByte(),
        t.dayOfMonth.toByte(),
        t.hour.toByte(),
        t.minute.toByte(),
        t.second.toByte(),
    )

    /** Entrée de liste (statut, nom, taille, indicateur) ou fin de liste (un seul octet). */
    fun listItem(payload: ByteArray): ListItem =
        if (payload.size < 1 + NAME_LENGTH + 4) ListItem.End
        else ListItem.Entry(
            ascii(payload, 1, NAME_LENGTH),
            uint32(payload, 1 + NAME_LENGTH),
            if (payload.size > 1 + NAME_LENGTH + 4) payload[1 + NAME_LENGTH + 4].toInt() and 0xff else -1,
        )

    /** Valeur de la trame `0x0b` qui termine l'envoi d'un fichier. */
    const val FILE_END = 0x02

    /** Taille annoncée par la réponse à `0x0b` (statut, puis taille sur 4 octets), ou null pour un payload plus court. */
    fun fileSize(payload: ByteArray): Long? = if (payload.size < 5) null else uint32(payload, 1)

    /** Payload de `0x0b` : le nom, puis la position de départ sur 4 octets. */
    fun fileRequest(name: String, offset: Long): ByteArray {
        val bytes = name.toByteArray(Charsets.US_ASCII)
        require(bytes.size == NAME_LENGTH) { "nom inattendu : $name" }
        return bytes + byteArrayOf(
            (offset ushr 24).toByte(),
            (offset ushr 16).toByte(),
            (offset ushr 8).toByte(),
            offset.toByte(),
        )
    }

    fun liveStart(payload: ByteArray): LiveStart? =
        if (payload.size < 1 + NAME_LENGTH + 4) null
        else LiveStart(status(payload), ascii(payload, 1, NAME_LENGTH), uint32(payload, 1 + NAME_LENGTH))

    fun liveEnd(payload: ByteArray): LiveEnd? =
        if (payload.size < 1 + NAME_LENGTH + 4) null
        else LiveEnd(status(payload), ascii(payload, 1, NAME_LENGTH), uint32(payload, 1 + NAME_LENGTH))

    fun newRecordName(payload: ByteArray): String? =
        if (payload.size < 1 + NAME_LENGTH) null else ascii(payload, 1, NAME_LENGTH)

    /** Premier octet FF et trois bits de poids fort du second à 1 (synchronisation d'une trame MP3). */
    fun isMp3Header(bytes: ByteArray): Boolean =
        bytes.size >= 2 && (bytes[0].toInt() and 0xff) == 0xff && (bytes[1].toInt() and 0xe0) == 0xe0

    private fun ascii(b: ByteArray, from: Int, len: Int): String =
        String(b, from, len, Charsets.US_ASCII).trimEnd('\u0000')

    private fun uint32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xff) shl 24) or ((b[at + 1].toLong() and 0xff) shl 16) or
            ((b[at + 2].toLong() and 0xff) shl 8) or (b[at + 3].toLong() and 0xff)
}
