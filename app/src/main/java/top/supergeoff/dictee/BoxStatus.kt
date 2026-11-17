package top.supergeoff.dictee

/** État du boîtier lu par la commande 0x05, dans le payload de 52 octets
 * (index sans en-tête ni CRC) : capacité totale 0 à 3, espace libre 4 à 7,
 * batterie 9, réduction du bruit 39, durée des segments 41, rétroéclairage 42. */
data class BoxStatus(
    val batteryPercent: Int,
    val totalMb: Long,
    val freeMb: Long,
    val noise: Int,
    val segmentDuration: Int,
    val backlight: Int,
) {
    companion object {
        const val PAYLOAD_LENGTH = 52

        fun parse(payload: ByteArray): BoxStatus {
            require(payload.size == PAYLOAD_LENGTH) {
                "état du boîtier : ${payload.size} octets, $PAYLOAD_LENGTH attendus"
            }
            return BoxStatus(
                batteryPercent = payload[9].toInt() and 0xff,
                totalMb = readUnsignedInt(payload, 0),
                freeMb = readUnsignedInt(payload, 4),
                noise = payload[39].toInt() and 0xff,
                segmentDuration = payload[41].toInt() and 0xff,
                backlight = payload[42].toInt() and 0xff,
            )
        }

        private fun readUnsignedInt(payload: ByteArray, index: Int): Long =
            ((payload[index].toLong() and 0xff) shl 24) or
                ((payload[index + 1].toLong() and 0xff) shl 16) or
                ((payload[index + 2].toLong() and 0xff) shl 8) or
                (payload[index + 3].toLong() and 0xff)
    }
}

/** Niveaux de réduction du bruit documentés (commande 0x17). */
enum class NoiseReduction(val raw: Int, val label: String) {
    DESACTIVE(0x00, "Désactivé"),
    FAIBLE(0x05, "Faible"),
    MOYEN(0x09, "Moyen"),
    ELEVE(0x0f, "Élevé"),
}

/** Durées de segment documentées (commande 0x16). */
enum class SegmentDuration(val raw: Int, val minutes: Int, val label: String) {
    MINUTES_30(0x00, 30, "30 minutes"),
    HEURE_1(0x01, 60, "1 heure"),
    HEURES_2(0x02, 120, "2 heures"),
    HEURES_3(0x03, 180, "3 heures"),
}

/** Libellé d'un réglage à partir de ses valeurs documentées ; « Inconnu » sinon. */
internal fun boxSettingLabel(raw: Int, labels: Map<Int, String>): String = labels[raw] ?: "Inconnu"

internal fun BoxStatus.storageLabel(): String {
    if (freeMb > totalMb) return "Stockage inconnu"
    val usedMb = totalMb - freeMb
    return "${formatMb(usedMb)} Mo utilisés sur ${formatMb(totalMb)} Mo"
}

private fun formatMb(value: Long): String =
    value.toString().reversed().chunked(3).joinToString(" ").reversed()
