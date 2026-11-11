package top.supergeoff.dictee

/** Phases conservées entre les lancements, sans présumer du résultat d'une soumission interrompue. */
enum class TranscriptionPhase {
    PENDING, SUBMITTING, UNKNOWN_SUBMISSION, TRACKING, CANCEL_REQUESTED, SAVING, COMPLETED, FAILED, CANCELLED
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
    val progress: Int? = null,
    val error: String? = null,
    val suspended: Boolean = false,
    val suspensionReason: String? = null,
)

enum class TranscriptionExclusion { MISSING_MP3, RECOVERY_FAILED }

data class SelectionResult(
    val candidates: List<Recording>,
    val excluded: Map<String, TranscriptionExclusion>,
)
