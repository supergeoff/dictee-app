package top.supergeoff.dictee

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Source d'une ligne du journal. */
enum class Source { APP, BLE, FS, STT, LLM, DRIVE }

/** Préfixe des messages d'erreur. */
private const val ERROR_PREFIX = "erreur : "

/** Durée de conservation d'une ligne, en heures. */
private const val KEPT_HOURS = 8L

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
private val LINE_FORMAT = Regex("""(\d{2}:\d{2}:\d{2}) ([A-Z]+) (.*)""")
private val FILE_FORMAT = Regex("""journal-\d{4}-\d{2}-\d{2}\.txt""")

/** Écriture dans le journal. Elle ne lève jamais d'exception d'entrée-sortie : une ligne qui ne s'écrit pas est perdue, sans rien planter. */
interface Log {
    fun add(source: Source, message: String)

    /** Écrit le message précédé de « erreur : ». */
    fun error(source: Source, message: String)
}

/** Ligne relue depuis un fichier du journal. */
data class JournalLine(val date: LocalDate, val time: String, val source: Source, val message: String) {
    val isError: Boolean
        get() = message.startsWith(ERROR_PREFIX)
}

/**
 * Journal écrit au fil de l'eau, dans `journal-AAAA-MM-JJ.txt` (un fichier par jour) dans [dir].
 * Une ligne a la forme `HH:mm:ss SOURCE message`. La date et l'heure viennent de [clock] à l'écriture.
 */
class Journal(private val dir: File, private val clock: () -> LocalDateTime) : Log {
    private val revisionFlow = MutableStateFlow(0L)

    /** Augmente à chaque ligne écrite et à chaque vidage, pour rafraîchir l'écran Journal. */
    val revision: StateFlow<Long> = revisionFlow.asStateFlow()

    @Synchronized
    override fun add(source: Source, message: String) {
        val now = clock()
        val text = message.lines().joinToString(" ")
        try {
            dir.mkdirs()
            File(dir, fileName(now.toLocalDate())).appendText("${now.format(TIME_FORMAT)} ${source.name} $text\n")
        } catch (e: IOException) {
            return   // disque plein ou dossier perdu : la ligne est perdue, sans révision ni exception
        }
        revisionFlow.value++
    }

    @Synchronized
    override fun error(source: Source, message: String) = add(source, ERROR_PREFIX + message)

    /**
     * Supprime les lignes de plus de huit heures. Un fichier antérieur à la veille est supprimé, un fichier récent est
     * réécrit sans ses lignes anciennes, et supprimé s'il n'en garde aucune.
     */
    @Synchronized
    fun purge() {
        val oldest = fileName(days().first())
        journalFiles().filter { it.name < oldest }.forEach { it.delete() }
        try {
            days().forEach { day ->
                val file = File(dir, fileName(day))
                if (!file.isFile) return@forEach
                val all = file.readLines().filter { it.isNotBlank() }
                val kept = all.filter { isRecent(day, it) }
                when {
                    kept.isEmpty() -> file.delete()
                    kept.size < all.size -> file.writeText(kept.joinToString("") { "$it\n" })
                }
            }
        } catch (e: IOException) {
            return   // la lecture filtre de toute façon les lignes anciennes
        }
    }

    /** Lignes des huit dernières heures, de la plus ancienne à la plus récente. Relit les fichiers à chaque appel. */
    fun lines(): List<JournalLine> = days().flatMap { day ->
        linesOf(day).mapNotNull { parse(day, it) }
    }

    /** Texte exporté : pour chaque jour non vide, `== AAAA-MM-JJ ==` puis ses lignes, les jours étant séparés par une ligne vide. */
    fun exportText(): String = days()
        .mapNotNull { day ->
            val text = linesOf(day).filter { it.isNotBlank() }  // déjà limitées aux huit dernières heures
            if (text.isEmpty()) null else "== $day ==\n" + text.joinToString("") { "$it\n" }
        }
        .joinToString("\n")

    /** Supprime toutes les lignes du journal. */
    @Synchronized
    fun clear() {
        journalFiles().forEach { it.delete() }
        revisionFlow.value++
    }

    /** La veille et le jour même : huit heures tiennent toujours sur ces deux jours. */
    private fun days(): List<LocalDate> {
        val today = clock().toLocalDate()
        return listOf(today.minusDays(1), today)
    }

    /** Lignes du fichier du jour [day] écrites dans les huit dernières heures. */
    private fun linesOf(day: LocalDate): List<String> =
        (File(dir, fileName(day)).takeIf { it.isFile }?.readLines() ?: emptyList()).filter { isRecent(day, it) }

    /** Vrai si la ligne a moins de huit heures. Une ligne illisible est gardée telle quelle, pour ne rien perdre en silence. */
    private fun isRecent(day: LocalDate, text: String): Boolean {
        val match = LINE_FORMAT.matchEntire(text) ?: return true
        val time = runCatching { LocalTime.parse(match.groupValues[1]) }.getOrNull() ?: return true
        return !LocalDateTime.of(day, time).isBefore(clock().minusHours(KEPT_HOURS))
    }

    private fun journalFiles(): List<File> = dir.listFiles().orEmpty().filter { FILE_FORMAT.matches(it.name) }

    /** Ligne du fichier du jour [day], ou null si elle ne suit pas la forme attendue. */
    private fun parse(day: LocalDate, text: String): JournalLine? {
        val match = LINE_FORMAT.matchEntire(text) ?: return null
        val source = Source.entries.firstOrNull { it.name == match.groupValues[2] } ?: return null
        return JournalLine(day, match.groupValues[1], source, match.groupValues[3])
    }

    private fun fileName(day: LocalDate) = "journal-$day.txt"
}

/** Filtre de l'écran Journal. */
enum class JournalFilter(val label: String) {
    ALL("Tout"), ERRORS("Erreurs"), BLE("Bluetooth"), DRIVE("Drive"), STT("Transcription"), LLM("Synthèse");

    fun accepts(line: JournalLine): Boolean = when (this) {
        ALL -> true
        ERRORS -> line.isError
        BLE -> line.source == Source.BLE
        DRIVE -> line.source == Source.DRIVE
        STT -> line.source == Source.STT
        LLM -> line.source == Source.LLM
    }
}

/**
 * Client HTTP qui journalise chaque requête : méthode, chemin, statut, durée et taille du corps de la réponse.
 * Les en-têtes, les paramètres et le corps ne sont jamais journalisés. Une exception est journalisée, puis relancée.
 */
class LoggingHttp(
    private val inner: Http,
    private val log: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) : Http {
    override fun call(request: HttpRequest): HttpResponse {
        val path = URI(request.url).rawPath
        val start = nanoTime()
        try {
            val response = inner.call(request)
            val ms = (nanoTime() - start) / 1_000_000
            val size = response.body.toByteArray(Charsets.UTF_8).size
            log("${request.method} $path ${response.code} · $ms ms · $size o")
            return response
        } catch (e: Exception) {
            log("${request.method} $path échec : ${e.message ?: e.javaClass.simpleName}")
            throw e
        }
    }
}
