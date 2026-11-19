package top.supergeoff.dictee

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/** Nom du carnet des locuteurs, avec l'instant (ms) de sa dernière utilisation. */
data class SpeakerName(val name: String, val lastUsedAt: Long)

/** Logique de texte des noms de locuteurs : étiquettes des voix d'un VTT, réécriture du VTT et du MD, carnet. */
object SpeakerNames {
    private const val MAX_CODE_POINTS = 40
    private val voice = Regex("""<v((?:\.[^ \t>]*)?)([ \t]+)([^>]*)>""")
    private val accents = Regex("""\p{Mn}+""")

    /** Étiquettes distinctes des balises `<v>`, dans l'ordre d'apparition. */
    fun labels(vtt: ByteArray): List<String> =
        voice.findAll(decodeUtf8(vtt)).map { it.groupValues[3].trim() }.filter { it.isNotEmpty() }.distinct().toList()

    /** Nom normalisé (`trim`) ou null s'il est invalide. */
    fun validate(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length) > MAX_CODE_POINTS) return null
        if (trimmed.any { it == '<' || it == '>' || it == '&' || it == '\n' || it == '\r' }) return null
        return trimmed
    }

    /** Remplace la valeur des voix égales à [from] ; lève [VttFormatException] si le résultat ne se relit pas. */
    fun renameInVtt(vtt: ByteArray, from: String, to: String): ByteArray {
        val text = decodeUtf8(vtt)
        val renamed = voice.replace(text) { match ->
            if (match.groupValues[3].trim() == from) "<v${match.groupValues[1]}${match.groupValues[2]}$to>" else match.value
        }
        val result = renamed.toByteArray(Charsets.UTF_8)
        WebVtt.parse(result)
        return result
    }

    /** Remplacement en mot entier ; renvoie null si [from] n'apparaît pas. */
    fun replaceInMd(md: ByteArray, from: String, to: String): ByteArray? {
        val word = Regex("""(?<![\p{L}\p{N}_])${Regex.escape(from)}(?![\p{L}\p{N}_])""")
        val text = decodeUtf8(md)
        if (!word.containsMatchIn(text)) return null
        return word.replace(text, Regex.escapeReplacement(to)).toByteArray(Charsets.UTF_8)
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

    private fun fold(text: String): String =
        accents.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()

    private fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        throw VttFormatException("Le fichier n'est pas de l'UTF-8 valide.")
    }
}
