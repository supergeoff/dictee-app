package top.supergeoff.dictee

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Fichier d'un enregistrement, présent dans le dossier du téléphone. */
data class PhoneFile(val fileName: String, val size: Long)

/** Contenu du dossier Drive : nom → taille, lu une fois par rafraîchissement ou par envoi. */
data class DriveListing(val files: Map<String, Long>)

/** État affiché sur la ligne d'un enregistrement, avec son libellé. */
sealed class DisplayState(val label: String) {
    data class Error(val message: String) : DisplayState("Erreur")
    data class Recovering(val percent: Int) : DisplayState("Récupération $percent %")
    data object Sending : DisplayState("Envoi…")
    /** [percent] nul : le serveur ne donne pas d'avancement. */
    data class Transcribing(val percent: Int?) : DisplayState(if (percent == null) "Transcription…" else "Transcription $percent %")
    data object OnDrive : DisplayState("Sur Drive")
    data object Ready : DisplayState("Prêt")
    data object Recovered : DisplayState("Récupéré")
    data object OnBox : DisplayState("Sur le boîtier")
}

/** Traitements en cours : le nom en cours de récupération avec son avancement, et le nom en cours d'envoi. */
data class Busy(
    val recovering: String? = null, val recoveringPercent: Int = 0, val sending: String? = null,
    val transcribing: String? = null, val transcribingPercent: Int? = null,
)

/** Enregistrement de la liste réunie. [phoneFiles] suit l'ordre des formats : MP3, VTT, MD. */
data class Recording(
    val name: String,
    val type: RecordType,
    val size: Long,
    val onBox: Boolean,
    val phoneFiles: Map<FileKind, PhoneFile>,
    val state: DisplayState,
)

/** Réunion du boîtier, du dossier du téléphone et du dossier Drive en une liste d'enregistrements. */
object Recordings {
    private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

    /** MP3 trouvé dans Drive : [type] vient du préfixe du nom, et reste null pour un nom de la version 0.1.5. */
    private class DriveMp3(val type: RecordType?, val size: Long)

    /**
     * Réunit les trois sources en une liste du plus récent au plus ancien, avec un enregistrement par nom à 14 chiffres.
     * Un enregistrement naît d'une entrée du boîtier, d'un fichier du téléphone reconnu par [RecordName.parse]
     * ou d'un MP3 de Drive. Une entrée du boîtier dont le nom n'est pas une date est ignorée.
     * [onUnknownFlag] reçoit le nom et l'indicateur d'une entrée du boîtier au type inconnu, qui compte alors comme une note.
     * Le type vient du boîtier, puis du préfixe d'un fichier du téléphone, puis du préfixe du MP3 de Drive, sinon Note.
     * La taille vient du boîtier, puis du MP3 du téléphone, puis du MP3 de Drive, sinon zéro.
     */
    fun merge(
        box: List<ListItem.Entry>,
        phone: List<PhoneFile>,
        drive: DriveListing?,
        errors: Map<String, Map<Step, String>>,
        busy: Busy,
        onUnknownFlag: (name: String, flag: Int) -> Unit = { _, _ -> },
    ): List<Recording> {
        val boxByName = box.filter { RecordName.dateTimeOrNull(it.name) != null }.associateBy { it.name }
        val phoneByName = phone
            .mapNotNull { file -> RecordName.parse(file.fileName)?.let { parsed -> parsed to file } }
            .groupBy { (parsed, _) -> parsed.name }
        val driveMp3ByName = drive?.let { driveMp3s(it) }.orEmpty()
        return (boxByName.keys + phoneByName.keys + driveMp3ByName.keys)
            .sortedDescending()   // 14 chiffres : l'ordre des noms est l'ordre chronologique
            .map { name ->
                val boxEntry = boxByName[name]
                val phoneEntries = phoneByName[name].orEmpty()
                val driveMp3 = driveMp3ByName[name]
                val phoneFiles = phoneEntries
                    .distinctBy { (parsed, _) -> parsed.kind }
                    .sortedBy { (parsed, _) -> parsed.kind }
                    .associate { (parsed, file) -> parsed.kind to file }
                Recording(
                    name = name,
                    type = typeOf(name, boxEntry, phoneEntries.firstOrNull()?.first?.type, driveMp3?.type, onUnknownFlag),
                    size = boxEntry?.size ?: phoneFiles[FileKind.MP3]?.size ?: driveMp3?.size ?: 0L,
                    onBox = boxEntry != null,
                    phoneFiles = phoneFiles,
                    state = stateOf(name, phoneFiles, drive, errors, busy),
                )
            }
    }

    /** Vrai si le MP3 est dans Drive, sous un nom préfixé (`Note-`, `Call-`) ou sous le nom de la version 0.1.5. */
    fun mp3InDrive(name: String, drive: DriveListing): Boolean =
        "$name.mp3" in drive.files || RecordType.entries.any { RecordName.fileName(it, name, FileKind.MP3) in drive.files }

    /** Fichiers du téléphone de cet enregistrement qui ne sont pas dans Drive. */
    fun missingInDrive(r: Recording, drive: DriveListing): List<PhoneFile> = missing(r.phoneFiles.values, drive)

    /**
     * Enregistrements du boîtier qui ne sont ni sur le téléphone ni dans Drive.
     * Sans liste Drive (`drive` null), seul le téléphone compte.
     */
    fun toRecover(list: List<Recording>, drive: DriveListing?): List<Recording> =
        list.filter { it.onBox && it.phoneFiles.isEmpty() && (drive == null || !mp3InDrive(it.name, drive)) }

    /**
     * Enregistrements que l'envoi peut prendre : ceux qui ont un fichier sur le téléphone, sauf ceux dont la récupération est en erreur.
     * Une récupération interrompue peut laisser un MP3 tronqué sous son nom définitif, et Drive ne remplace jamais un fichier :
     * l'envoyer le figerait tronqué. Une erreur d'une autre étape n'exclut rien.
     */
    fun sendable(list: List<Recording>, errors: Map<String, Map<Step, String>>): List<Recording> =
        list.filter { it.phoneFiles.isNotEmpty() && Step.RECOVER !in errors[it.name].orEmpty() }

    /**
     * Enregistrements de [sendable] qui ont au moins un fichier du téléphone absent de Drive.
     * Sans liste Drive (`drive` null), tous ceux de [sendable].
     */
    fun toSend(list: List<Recording>, drive: DriveListing?, errors: Map<String, Map<Step, String>> = emptyMap()): List<Recording> {
        val candidates = sendable(list, errors)
        return if (drive == null) candidates else candidates.filter { missingInDrive(it, drive).isNotEmpty() }
    }

    /** Sélectionne les MP3 locaux et signale les exclusions. */
    fun toTranscribe(
        recordings: List<Recording>, selectedNames: Set<String>?, errors: Map<String, Map<Step, String>>,
        activeNames: Set<String> = emptySet(),
    ): SelectionResult {
        val candidates = mutableListOf<Recording>()
        val excluded = linkedMapOf<String, TranscriptionExclusion>()
        for (recording in recordings.sortedBy { it.name }) {
            if (selectedNames != null && recording.name !in selectedNames) continue
            if (selectedNames == null && FileKind.VTT in recording.phoneFiles) continue
            when {
                recording.name in activeNames -> excluded[recording.name] = TranscriptionExclusion.ALREADY_ACTIVE
                FileKind.MP3 !in recording.phoneFiles -> excluded[recording.name] = TranscriptionExclusion.MISSING_MP3
                Step.RECOVER in errors[recording.name].orEmpty() -> excluded[recording.name] = TranscriptionExclusion.RECOVERY_FAILED
                else -> candidates += recording
            }
        }
        selectedNames?.minus(recordings.map { it.name }.toSet())?.sorted()?.forEach {
            excluded[it] = TranscriptionExclusion.MISSING_MP3
        }
        return SelectionResult(candidates, excluded)
    }

    /** « Aujourd'hui », « Hier », sinon le jour en toutes lettres, par exemple « Jeudi 29 octobre ». */
    fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Aujourd'hui"
        today.minusDays(1) -> "Hier"
        else -> date.format(dayFormat).replaceFirstChar { it.uppercase(Locale.FRENCH) }
    }

    /** Regroupe par jour les enregistrements consécutifs de même date, dans l'ordre de la liste. */
    fun groupByDay(list: List<Recording>, today: LocalDate): List<Pair<String, List<Recording>>> {
        val days = mutableListOf<Pair<LocalDate, MutableList<Recording>>>()
        for (recording in list) {
            val date = RecordName.dateTime(recording.name).toLocalDate()
            if (days.lastOrNull()?.first == date) days.last().second += recording
            else days += date to mutableListOf(recording)
        }
        return days.map { (date, recordings) -> dayLabel(date, today) to recordings }
    }

    /** MP3 de Drive par nom à 14 chiffres. Un nom préfixé l'emporte sur un nom de la version 0.1.5. */
    private fun driveMp3s(drive: DriveListing): Map<String, DriveMp3> {
        val legacy = drive.files.mapNotNull { (fileName, size) ->
            RecordName.legacyMp3(fileName)?.let { it to DriveMp3(null, size) }
        }
        val prefixed = drive.files.mapNotNull { (fileName, size) ->
            RecordName.parse(fileName)?.takeIf { it.kind == FileKind.MP3 }?.let { it.name to DriveMp3(it.type, size) }
        }
        return (legacy + prefixed).toMap()
    }

    private fun typeOf(
        name: String,
        boxEntry: ListItem.Entry?,
        phoneType: RecordType?,
        driveType: RecordType?,
        onUnknownFlag: (name: String, flag: Int) -> Unit,
    ): RecordType =
        if (boxEntry != null) RecordType.fromFlag(boxEntry.flag) ?: RecordType.NOTE.also { onUnknownFlag(name, boxEntry.flag) }
        else phoneType ?: driveType ?: RecordType.NOTE

    /** Première règle vraie : erreur, récupération en cours, envoi en cours, Sur Drive, Prêt, Récupéré, Sur le boîtier. */
    private fun stateOf(
        name: String,
        phoneFiles: Map<FileKind, PhoneFile>,
        drive: DriveListing?,
        errors: Map<String, Map<Step, String>>,
        busy: Busy,
    ): DisplayState {
        // Plusieurs erreurs : le message de la première étape dans l'ordre de [Step].
        val error = errors[name]?.minByOrNull { it.key.ordinal }?.value
        return when {
            error != null -> DisplayState.Error(error)
            busy.recovering == name -> DisplayState.Recovering(busy.recoveringPercent)
            busy.sending == name -> DisplayState.Sending
            busy.transcribing == name -> DisplayState.Transcribing(busy.transcribingPercent)
            drive != null && mp3InDrive(name, drive) && missing(phoneFiles.values, drive).isEmpty() -> DisplayState.OnDrive
            phoneFiles.keys.containsAll(FileKind.entries) -> DisplayState.Ready
            phoneFiles.isNotEmpty() -> DisplayState.Recovered
            else -> DisplayState.OnBox
        }
    }

    private fun missing(phoneFiles: Collection<PhoneFile>, drive: DriveListing): List<PhoneFile> =
        phoneFiles.filterNot { inDrive(it.fileName, drive) }

    /** Un fichier est dans Drive s'il y existe sous le même nom ; pour un MP3, le nom de la version 0.1.5 compte aussi. */
    private fun inDrive(fileName: String, drive: DriveListing): Boolean {
        if (fileName in drive.files) return true
        val parsed = RecordName.parse(fileName) ?: return false
        return parsed.kind == FileKind.MP3 && "${parsed.name}.mp3" in drive.files
    }
}

/** Nature d'un traitement suivi par une barre d'avancement. */
enum class ProgressKind(val label: String) { RECOVERY("Récupération"), SEND("Envoi"), TRANSCRIPTION("Transcription") }

/** Avancement : fichier [index] sur [total], [percent] pour cent du fichier en cours. */
data class Progress(val kind: ProgressKind, val index: Int, val total: Int, val percent: Int) {
    /** Libellé de la barre, par exemple « Récupération 1/2 · 40 % ». */
    fun label(): String = if (percent == UNKNOWN) "${kind.label} $index/$total" else "${kind.label} $index/$total · $percent %"

    companion object {
        /** Avancement non fourni : barre indéterminée. */
        const val UNKNOWN = -1
    }
}
