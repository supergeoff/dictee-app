package top.supergeoff.dictee

/** Étape persistée d'une demande déclenchée par une récupération explicite. */
enum class AutomaticProcessingPhase { RECOVERING, READY, WAITING_SETTINGS }

/** Intention de traitement attachée à une récupération et à son dossier local. */
data class AutomaticProcessingRequest(
    val id: String,
    val recordingName: String,
    val folderUri: String,
    val phase: AutomaticProcessingPhase,
    val reason: String? = null,
)
