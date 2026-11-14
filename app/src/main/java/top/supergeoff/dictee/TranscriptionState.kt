package top.supergeoff.dictee

/** Phases conservées entre les lancements, sans présumer du résultat d'une soumission interrompue. */
enum class TranscriptionPhase {
    PENDING, SUBMITTING, UNKNOWN_SUBMISSION, TRACKING, CANCEL_REQUESTED, SAVING, COMPLETED, FAILED, CANCELLED, SYNTHESIZING
}

internal fun TranscriptionPhase.isTerminal(): Boolean = when (this) {
    TranscriptionPhase.COMPLETED, TranscriptionPhase.FAILED, TranscriptionPhase.CANCELLED -> true
    else -> false
}

/** Contexte figé d'une tâche. [encryptedKeyRef] est une référence, jamais la clé en clair. */
data class TranscriptionTask(
    val localId: String,
    val recordingName: String,
    val mp3Name: String,
    val vttName: String,
    val folderUri: String,
    val mp3Sha256: String,
    val serverAddress: String,
    val encryptedKeyRef: String,
    val language: String,
    val model: String,
    val phase: TranscriptionPhase,
    val serverId: String? = null,
    /** Fraction de la progression serveur, de 0 à 1, ou null si inconnue. */
    val progress: Double? = null,
    val error: String? = null,
    val suspended: Boolean = false,
    val suspensionReason: String? = null,
    val synthesis: SynthesisWork? = null,
    val replaceVtt: Boolean = true,
)

enum class TranscriptionExclusion { MISSING_MP3, RECOVERY_FAILED, ALREADY_ACTIVE }

data class SelectionResult(
    val candidates: List<Recording>,
    val excluded: Map<String, TranscriptionExclusion>,
)

/** Avancement affiché, déduit des tâches durables : la plus ancienne tâche active et non suspendue. */
object TranscriptionProgress {
    fun current(tasks: List<TranscriptionTask>): Pair<String, Progress>? {
        val active = tasks.filter { !it.phase.isTerminal() && !it.suspended }.sortedBy { it.recordingName }
        val first = active.firstOrNull() ?: return null
        val percent = first.progress?.let { (it * 100).toInt().coerceIn(0, 100) } ?: Progress.UNKNOWN
        return first.recordingName to Progress(ProgressKind.TRANSCRIPTION, 1, active.size, percent)
    }
}
