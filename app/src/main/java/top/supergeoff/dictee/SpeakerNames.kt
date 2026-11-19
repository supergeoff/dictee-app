package top.supergeoff.dictee

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/** Nom du carnet des locuteurs, avec l'instant (ms) de sa dernière utilisation. */
data class SpeakerName(val name: String, val lastUsedAt: Long)

/** Logique de texte des noms de locuteurs : étiquettes des voix d'un VTT, réécriture du VTT, carnet. */
object SpeakerNames {
    private const val MAX_CODE_POINTS = 40
    private val voice = Regex("""^<v((?:\.[^ \t>]*)?)([ \t]+)([^>]*)>""")
    private val lineWithEnding = Regex("""([^\r\n]*)(\r\n|\r|\n|$)""")
    private val accents = Regex("""\p{Mn}+""")

    /** Étiquettes distinctes des balises `<v>` ouvrant le payload d'un segment, dans l'ordre d'apparition. */
    fun labels(vtt: ByteArray): List<String> {
        val lines = splitLines(decodeUtf8(vtt)).map { it.first }
        return voiceLineIndexes(lines).mapNotNull { voice.find(lines[it])?.groupValues?.get(3)?.trim() }
            .filter { it.isNotEmpty() }.distinct()
    }

    /** Nom normalisé (`trim`) ou null s'il est invalide. */
    fun validate(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length) > MAX_CODE_POINTS) return null
        if (trimmed.any { it == '<' || it == '>' || it == '&' || it == '\n' || it == '\r' }) return null
        return trimmed
    }

    /**
     * Remplace en une passe la valeur des balises `<v>` qui figurent dans [renames] (étiquette vers nom) au début du payload d'un segment,
     * comme la lecture de [WebVtt] ; une étiquette absente de [renames] reste inchangée, et deux étiquettes envoyées vers le même nom
     * fusionnent. Le reste du fichier garde ses octets. Lève [VttFormatException] si le résultat ne se relit pas.
     */
    fun renameInVtt(vtt: ByteArray, renames: Map<String, String>): ByteArray {
        val lines = splitLines(decodeUtf8(vtt)).toMutableList()
        for (index in voiceLineIndexes(lines.map { it.first })) {
            val (content, ending) = lines[index]
            val renamed = voice.replace(content) { match ->
                val to = renames[match.groupValues[3].trim()]
                if (to != null) "<v${match.groupValues[1]}${match.groupValues[2]}$to>" else match.value
            }
            lines[index] = renamed to ending
        }
        val result = lines.joinToString("") { it.first + it.second }.toByteArray(Charsets.UTF_8)
        WebVtt.parse(result)
        return result
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
