package top.supergeoff.dictee

data class ProcessingResultNotice(val taskId: String, val title: String, val text: String)

fun processingResultNotice(previous: TranscriptionTask, next: TranscriptionTask): ProcessingResultNotice? {
    if (previous == next || previous.phase in setOf(TranscriptionPhase.CANCEL_REQUESTED, TranscriptionPhase.CANCELLED) ||
        next.phase in setOf(TranscriptionPhase.CANCEL_REQUESTED, TranscriptionPhase.CANCELLED)) return null
    val success = next.phase == TranscriptionPhase.COMPLETED && next.synthesis?.phase == SynthesisPhase.COMPLETED
    val wasSuccess = previous.phase == TranscriptionPhase.COMPLETED && previous.synthesis?.phase == SynthesisPhase.COMPLETED
    val label = RecordName.parse(next.mp3Name)?.let { RecordName.detailTitle(it.type, it.name) } ?: "Enregistrement"
    if (success && !wasSuccess) return ProcessingResultNotice(next.localId, "Traitement terminé", "$label — VTT et MD créés.")
    val reason = when {
        next.phase == TranscriptionPhase.FAILED && previous.phase != TranscriptionPhase.FAILED -> next.error?.takeIf { it.isNotBlank() } ?: "Le traitement a échoué."
        next.suspended && !previous.suspended -> next.suspensionReason
        next.synthesis?.phase == SynthesisPhase.SUSPENDED && previous.synthesis?.phase != SynthesisPhase.SUSPENDED -> next.synthesis.suspensionReason
        else -> null
    }
    return reason?.takeIf { it.isNotBlank() }?.let { ProcessingResultNotice(next.localId, "Traitement interrompu", "$label — $it") }
}
