package top.supergeoff.dictee

enum class SynthesisPhase { WAITING_VTT, READY, CALLING, SAVE_PENDING, SUSPENDED, COMPLETED, CANCELLED }

data class PromptMetadata(val type: String, val date: String, val duree: String)

data class SynthesisContext(
    val baseUrl: String,
    val credentialRef: String,
    val model: String,
    val promptTemplate: String,
    val metadata: PromptMetadata,
    val systemPrompt: String?,
    val vttSha256: String?,
)

data class SynthesisWork(
    val phase: SynthesisPhase,
    val mdName: String,
    val replaceMd: Boolean,
    val context: SynthesisContext?,
    val attempts: Int,
    val nextAttemptAtMillis: Long?,
    val stagedSha256: String?,
    val suspensionReason: String?,
)

internal fun hasValidSynthesisContext(work: SynthesisWork): Boolean = work.context != null ||
    work.phase in setOf(SynthesisPhase.WAITING_VTT, SynthesisPhase.SUSPENDED, SynthesisPhase.CANCELLED)

data class SynthesisProvenance(
    val folderUri: String,
    val mdName: String,
    val model: String,
    val vttSha256: String,
    val mdSha256: String,
)
