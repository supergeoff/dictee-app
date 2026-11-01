package top.supergeoff.dictee

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

/** Type d'enregistrement, lu dans l'indicateur de la liste (octet 19 du payload). */
enum class RecordType(val label: String) {
    NOTE("Note"), CALL("Call");

    companion object {
        /** 0x01 pour une note, 0x00 pour un appel, null pour toute autre valeur. */
        fun fromFlag(flag: Int): RecordType? = when (flag) {
            0x01 -> NOTE
            0x00 -> CALL
            else -> null
        }
    }
}

/** Format d'un fichier de l'enregistrement. */
enum class FileKind(val ext: String, val mime: String) {
    MP3("mp3", "audio/mpeg"), VTT("vtt", "text/vtt"), MD("md", "text/markdown")
}

/** Fichier d'un enregistrement : son type, son horodatage (AAAAMMJJHHMMSS) et son format. */
data class RecordFile(val type: RecordType, val name: String, val kind: FileKind) {
    val fileName: String
        get() = RecordName.fileName(type, name, kind)
}

/** Noms de fichiers, durées, tailles et titres des enregistrements. */
object RecordName {
    private const val KILOBYTE = 1024L
    private const val MEGABYTE = 1_048_576L

    private val fileNameRegex = Regex("""^(Note|Call)-(\d{14})\.(mp3|vtt|md)$""")
    private val legacyMp3Regex = Regex("""^(\d{14})\.mp3$""")
    private val timestampFormat = DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT)
    private val rowTimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)
    private val detailTimeFormat = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.FRENCH)

    /** Lit un nom comme `Call-20261030160502.mp3`, ou renvoie null si le nom ne suit pas ce format ou si sa date n'existe pas. */
    fun parse(fileName: String): RecordFile? {
        val match = fileNameRegex.matchEntire(fileName) ?: return null
        val (label, name, ext) = match.destructured
        if (dateTimeOrNull(name) == null) return null
        return RecordFile(
            type = RecordType.entries.first { it.label == label },
            name = name,
            kind = FileKind.entries.first { it.ext == ext },
        )
    }

    /** Nom sans extension d'un fichier de la version 0.1.5 (`AAAAMMJJHHMMSS.mp3`), ou null si le nom ne suit pas ce format ou si sa date n'existe pas. */
    fun legacyMp3(fileName: String): String? =
        legacyMp3Regex.matchEntire(fileName)?.let { it.groupValues[1] }?.takeIf { dateTimeOrNull(it) != null }

    /** Nom de fichier d'un enregistrement, par exemple `Call-20261030160502.mp3`. */
    fun fileName(type: RecordType, name: String, kind: FileKind): String =
        "${type.label}-$name.${kind.ext}"

    /** Date et heure de l'enregistrement, lues dans son nom. Lève une exception si le nom n'est pas une date qui existe. */
    fun dateTime(name: String): LocalDateTime = LocalDateTime.parse(name, timestampFormat)

    /** Date et heure lues dans le nom, ou null si le nom n'est pas une date qui existe. */
    fun dateTimeOrNull(name: String): LocalDateTime? =
        try {
            dateTime(name)
        } catch (e: DateTimeParseException) {
            null
        }

    /** Durée en secondes, estimée à 4000 octets par seconde. */
    fun durationSeconds(size: Long): Long = size / 4000

    /** Durée affichée : « 45 s » sous une minute, sinon des minutes arrondies au plus proche. */
    fun formatDuration(seconds: Long): String =
        if (seconds < 60) "$seconds s" else "${(seconds + 30) / 60} min"

    /** Taille affichée : des Ko arrondis au supérieur sous 1 Mo, sinon des Mo à une décimale, avec une virgule. */
    fun formatSize(bytes: Long): String =
        if (bytes < MEGABYTE) "${(bytes + KILOBYTE - 1) / KILOBYTE} Ko"
        else {
            val tenths = (bytes * 10 + MEGABYTE / 2) / MEGABYTE
            "${tenths / 10},${tenths % 10} Mo"
        }

    /** Titre d'une ligne de la liste, par exemple « Call · 16:05 ». */
    fun rowTitle(type: RecordType, name: String): String =
        "${type.label} · ${dateTime(name).format(rowTimeFormat)}"

    /** Titre de la fiche d'un enregistrement, par exemple « Call · 30 oct. 16:05 ». */
    fun detailTitle(type: RecordType, name: String): String =
        "${type.label} · ${dateTime(name).format(detailTimeFormat)}"
}
