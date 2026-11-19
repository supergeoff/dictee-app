package top.supergeoff.dictee

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/** Nom du carnet des locuteurs, avec l'instant (ms) de sa dernière utilisation. */
data class SpeakerName(val name: String, val lastUsedAt: Long)

/**
 * Locuteur d'un VTT : [current] est la valeur de la balise `<v>` (le nom affiché), [original] l'étiquette d'origine gardée dans la classe
 * `spk-` de la balise, ou la valeur elle-même tant qu'aucun renommage n'a eu lieu. [original] est null quand l'étiquette ne se
 * sait pas garder (caractères hors `[A-Za-z0-9_-]`) : le retour à l'origine est alors impossible.
 */
data class SpeakerLabel(val current: String, val original: String?)

/** Logique de texte des noms de locuteurs : étiquettes des voix d'un VTT, réécriture du VTT, carnet. */
object SpeakerNames {
    private const val MAX_CODE_POINTS = 40
    private val voice = Regex("""^<v((?:\.[^ \t>]*)?)([ \t]+)([^>]*)>""")
    private val originalPattern = Regex("""[A-Za-z0-9_-]+""")
    private const val ORIGINAL_CLASS_PREFIX = "spk-"
    private val lineWithEnding = Regex("""([^\r\n]*)(\r\n|\r|\n|$)""")
    private val accents = Regex("""\p{Mn}+""")

    /** Locuteurs distincts (nom courant et origine) des balises `<v>` ouvrant le payload d'un segment, dans l'ordre d'apparition. */
    fun labels(vtt: ByteArray): List<SpeakerLabel> {
        val lines = splitLines(decodeUtf8(vtt)).map { it.first }
        return voiceLineIndexes(lines).mapNotNull { voice.find(lines[it])?.let(::labelOf) }.distinct()
    }

    /** Nom normalisé (`trim`) ou null s'il est invalide. */
    fun validate(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length) > MAX_CODE_POINTS) return null
        if (trimmed.any { it == '<' || it == '>' || it == '&' || it == '\n' || it == '\r' }) return null
        return trimmed
    }

    /**
     * Remplace en une passe la valeur des balises `<v>` dont le locuteur figure dans [renames] (locuteur vers nom) au début du payload
     * d'un segment, comme la lecture de [WebVtt] ; un locuteur absent de [renames] reste inchangé. Une balise renommée garde l'étiquette
     * d'origine dans une classe `spk-` (`<v.spk-A Marie>`), ajoutée au premier renommage et conservée ensuite ; les autres classes sont
     * préservées. Un nom égal à l'origine du locuteur la rétablit : la classe `spk-` disparaît (`<v A>`). Deux locuteurs envoyés vers le
     * même nom fusionnent par le nom, chacun gardant son origine. Le reste du fichier garde ses octets. Lève [VttFormatException] si le
     * résultat ne se relit pas.
     */
    fun renameInVtt(vtt: ByteArray, renames: Map<SpeakerLabel, String>): ByteArray {
        val lines = splitLines(decodeUtf8(vtt)).toMutableList()
        for (index in voiceLineIndexes(lines.map { it.first })) {
            val (content, ending) = lines[index]
            val renamed = voice.replace(content) { match ->
                val label = labelOf(match)
                val to = label?.let { renames[it] }
                if (label == null || to == null) match.value else renamedTag(match, label, to)
            }
            lines[index] = renamed to ending
        }
        val result = lines.joinToString("") { it.first + it.second }.toByteArray(Charsets.UTF_8)
        WebVtt.parse(result)
        return result
    }

    private fun classesOf(match: MatchResult): List<String> = match.groupValues[1].split('.').filter { it.isNotEmpty() }

    private fun originalClassIndex(classes: List<String>): Int = classes.indexOfFirst {
        it.startsWith(ORIGINAL_CLASS_PREFIX) && originalPattern.matches(it.removePrefix(ORIGINAL_CLASS_PREFIX))
    }

    private fun labelOf(match: MatchResult): SpeakerLabel? {
        val current = match.groupValues[3].trim()
        if (current.isEmpty()) return null
        return SpeakerLabel(current, classesOf(match).let { classes ->
            originalClassIndex(classes).takeIf { it >= 0 }?.let { classes[it].removePrefix(ORIGINAL_CLASS_PREFIX) }
        } ?: current.takeIf { originalPattern.matches(it) })
    }

    private fun renamedTag(match: MatchResult, label: SpeakerLabel, to: String): String {
        val classes = classesOf(match)
        val originalIndex = originalClassIndex(classes)
        val renamedClasses = when {
            originalIndex >= 0 && to == label.original -> classes.filterIndexed { i, _ -> i != originalIndex }
            originalIndex >= 0 || label.original == null || to == label.original -> classes
            else -> classes + (ORIGINAL_CLASS_PREFIX + label.original)
        }
        val classText = if (renamedClasses == classes) match.groupValues[1] else renamedClasses.joinToString("") { ".$it" }
        return "<v$classText${match.groupValues[2]}$to>"
    }

    /** Ajoute ou rafraîchit un nom (égalité sans casse ni accents), le plus récent en tête. */
    fun use(book: List<SpeakerName>, name: String, now: Long): List<SpeakerName> =
        listOf(SpeakerName(name, now)) + book.filterNot { fold(it.name) == fold(name) }

    /** Filtre sans casse ni accents, du plus récent au moins récent. */
    fun suggestions(book: List<SpeakerName>, query: String): List<SpeakerName> {
        val needle = fold(query.trim())
        return book.filter { fold(it.name).contains(needle) }.sortedByDescending { it.lastUsedAt }
    }

    /** Renomme [old] en [new] dans le carnet ; un nom déjà présent sous [new] est fusionné. */
    fun rename(book: List<SpeakerName>, old: String, new: String): List<SpeakerName> {
        val source = book.firstOrNull { fold(it.name) == fold(old) } ?: return book
        val merged = book.filter { fold(it.name) == fold(new) }.maxOfOrNull { it.lastUsedAt } ?: Long.MIN_VALUE
        val renamed = SpeakerName(new, maxOf(source.lastUsedAt, merged))
        return book.mapNotNull {
            when {
                it === source -> renamed
                fold(it.name) == fold(new) -> null
                else -> it
            }
        }
    }

    fun remove(book: List<SpeakerName>, name: String): List<SpeakerName> =
        book.filterNot { fold(it.name) == fold(name) }

    /** Lignes du texte avec leur fin de ligne d'origine (`\r\n`, `\r`, `\n` ou vide pour la dernière). */
    private fun splitLines(text: String): List<Pair<String, String>> {
        val lines = lineWithEnding.findAll(text).map { it.groupValues[1] to it.groupValues[2] }.toList()
        return if (lines.size > 1 && lines.last() == ("" to "")) lines.dropLast(1) else lines
    }

    /** Indices des premières lignes de payload des segments : ni en-tête, ni NOTE, STYLE ou REGION. */
    private fun voiceLineIndexes(lines: List<String>): List<Int> {
        val indexes = mutableListOf<Int>()
        var start = 1
        while (start < lines.size) {
            if (lines[start].isEmpty()) { start++; continue }
            var end = start
            while (end < lines.size && lines[end].isNotEmpty()) end++
            val first = lines[start]
            val isCue = !(first == "STYLE" || first == "REGION" ||
                first.startsWith("NOTE") && (first.length == 4 || first[4] == ' ' || first[4] == '\t'))
            if (isCue) {
                val timing = (start until end).firstOrNull { "-->" in lines[it] }
                if (timing != null && timing - start <= 1 && timing + 1 < end) indexes.add(timing + 1)
            }
            start = end
        }
        return indexes
    }

    private fun fold(text: String): String =
        accents.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()

    private fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        throw VttFormatException("Le fichier n'est pas de l'UTF-8 valide.")
    }
}
