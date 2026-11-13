package top.supergeoff.dictee

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Segment lisible d'un VTT. [speaker] vient d'une balise `<v …>`. */
data class VttCue(val startMs: Long, val endMs: Long, val text: String, val speaker: String? = null)

class VttFormatException(message: String) : Exception(message)

/**
 * Lecture d'un WebVTT pour l'affichage uniquement : le fichier écrit sur le téléphone reste les octets reçus.
 * Un fichier valide sans segment donne une liste vide ; tout le reste lève une [VttFormatException].
 */
object WebVtt {
    private val timing = Regex("""^((?:\d+:)?\d{2}:\d{2}\.\d{3})[ \t]+-->[ \t]+((?:\d+:)?\d{2}:\d{2}\.\d{3})(?:[ \t].*)?$""")
    private val voice = Regex("""^<v(?:\.[^ \t>]*)?[ \t]+([^>]*)>""")
    private val tags = Regex("""</?[^>]*>""")

    fun parse(bytes: ByteArray): List<VttCue> {
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw VttFormatException("Le VTT n'est pas de l'UTF-8 valide.")
        }
        val lines = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val header = lines.first()
        if (!header.startsWith("WEBVTT") || (header.length > 6 && header[6] != ' ' && header[6] != '\t'))
            throw VttFormatException("En-tête WEBVTT absent.")
        val cues = mutableListOf<VttCue>()
        var block = mutableListOf<String>()
        fun flush() {
            if (block.isNotEmpty()) cue(block)?.let(cues::add)
            block = mutableListOf()
        }
        for (line in lines.drop(1)) if (line.isEmpty()) flush() else block.add(line)
        flush()
        return cues
    }

    private fun cue(block: List<String>): VttCue? {
        val first = block.first()
        if (first == "STYLE" || first == "REGION" || first.startsWith("NOTE") && (first.length == 4 || first[4] == ' ' || first[4] == '\t')) return null
        val at = block.indexOfFirst { "-->" in it }.takeIf { it in 0..1 } ?: throw VttFormatException("Segment sans horodatage.")
        val match = timing.matchEntire(block[at]) ?: throw VttFormatException("Horodatage invalide.")
        val start = millis(match.groupValues[1])
        val end = millis(match.groupValues[2])
        if (end < start) throw VttFormatException("Horodatage de fin avant le début.")
        val payload = block.drop(at + 1).joinToString("\n")
        val speaker = voice.find(payload)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
        return VttCue(start, end, decode(tags.replace(payload, "")), speaker)
    }

    private fun millis(stamp: String): Long {
        val parts = stamp.split(':')
        val (seconds, ms) = parts.last().split('.')
        val minutes = parts[parts.size - 2].toLong()
        val hours = if (parts.size == 3) parts[0].toLong() else 0L
        if (minutes > 59 || seconds.toLong() > 59) throw VttFormatException("Horodatage invalide.")
        return ((hours * 60 + minutes) * 60 + seconds.toLong()) * 1000 + ms.toLong()
    }

    private fun decode(text: String): String = text
        .replace("&lt;", "<").replace("&gt;", ">").replace("&lrm;", "\u200E").replace("&rlm;", "\u200F")
        .replace("&nbsp;", "\u00A0").replace("&amp;", "&")
}
