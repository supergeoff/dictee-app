package top.supergeoff.dictee

import java.time.format.DateTimeFormatter
import java.util.Locale

data class PromptVariables(val type: String, val date: String, val duree: String, val intervenants: String)

object SynthesisPrompt {
    val DEFAULT: String = DEFAULT_SYNTHESIS_PROMPT
    // Sans état statique : un initialiseur de classe a déjà fait échouer tout le parcours MD sous Android (DIC-25).
    private val dateFormat: DateTimeFormatter
        get() = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm", Locale.FRENCH)
    // Accolades fermantes échappées : le moteur ICU d'Android rejette un « } » nu (erreur de syntaxe),
    // contrairement à OpenJDK des tests JVM — la forme échappée est valide partout.
    private val variablePattern: Regex
        get() = Regex("""\{\{(type|date|duree|intervenants)\}\}""")
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
