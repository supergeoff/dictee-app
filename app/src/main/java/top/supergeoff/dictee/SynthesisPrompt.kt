package top.supergeoff.dictee

import java.time.format.DateTimeFormatter
import java.util.Locale

data class PromptVariables(val type: String, val date: String, val duree: String, val intervenants: String)

object SynthesisPrompt {
    val DEFAULT: String = DEFAULT_SYNTHESIS_PROMPT
    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm", Locale.FRENCH)
    private val variablePattern = Regex("\\{\\{(type|date|duree|intervenants)}}")
    private const val UNAVAILABLE = "Non disponible"

    fun variables(recording: Recording, vtt: ByteArray): PromptVariables {
        val speakers = WebVtt.parse(vtt).mapNotNull { it.speaker }.distinct()
        return PromptVariables(
            type = recording.type.label,
            date = RecordName.dateTime(recording.name).format(dateFormat),
            duree = if (recording.size > 0) RecordName.formatDuration(RecordName.durationSeconds(recording.size)) else UNAVAILABLE,
            intervenants = speakers.joinToString(", ").ifEmpty { UNAVAILABLE },
        )
    }

    fun render(template: String, values: PromptVariables): String = variablePattern.replace(template) { match ->
        when (match.groupValues[1]) {
            "type" -> values.type
            "date" -> values.date
            "duree" -> values.duree
            else -> values.intervenants
        }
    }
}
