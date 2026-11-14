package top.supergeoff.dictee

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Présentation commune à la fiche et à la liste, calculée sans dépendance Android. */
enum class PipelineStage { PHONE, VTT, MD, DRIVE }
enum class PipelineStatus { DONE, RUNNING, WAITING, TODO, ERROR }
data class PipelineStep(val stage: PipelineStage, val status: PipelineStatus, val label: String, val reason: String? = null)
data class PipelineReason(val stage: PipelineStage, val text: String)
enum class PipelineAction {
    CANCEL_VTT, CANCEL_MD, REMOVE_FROM_QUEUE, RETRY_RECOVERY, RECOVER, RETRY_VTT,
    CREATE_VTT_AND_MD, CREATE_VTT, RETRY_MD, OPEN_SETTINGS, CREATE_MD, REDO_MD, RETRY_SEND, SEND,
}
data class PipelineButton(val action: PipelineAction, val label: String, val enabled: Boolean = true, val secondary: Boolean = false)
data class PipelinePresentation(
    val steps: List<PipelineStep>, val reason: PipelineReason?, val button: PipelineButton?, val badge: String, val queueRank: Int?,
)
data class PipelineInput(
    val recording: Recording, val local: LocalState, val busy: Busy, val drive: DriveListing?, val connected: Boolean,
    val synthesisConfigured: Boolean, val provenance: ProvenanceStatus = ProvenanceStatus.UNKNOWN,
)

object RecordingPipeline {
    fun calculate(input: PipelineInput): PipelinePresentation {
        val r = input.recording
        val errors = input.local.errors[r.name].orEmpty()
        val active = input.local.transcriptionTasks.filter { !it.phase.isTerminal() && !it.suspended }.sortedBy { it.recordingName }
        val rank = active.indexOfFirst { it.recordingName == r.name }.let { if (it < 0) null else it + 1 }
        val task = active.firstOrNull { it.recordingName == r.name }
            ?: input.local.transcriptionTasks.lastOrNull { it.recordingName == r.name }
        val synthesis = task?.synthesis
        val inSynthesis = task?.phase == TranscriptionPhase.SYNTHESIZING
        val hasMp3 = FileKind.MP3 in r.phoneFiles
        val hasVtt = FileKind.VTT in r.phoneFiles
        val hasMd = FileKind.MD in r.phoneFiles
        val waitingReason = rank?.let { "${ordinal(it)} dans la file. Aucun appel n'est parti." }
        val phone = when {
            hasMp3 -> step(PipelineStage.PHONE, PipelineStatus.DONE, "Fait")
            input.busy.recovering == r.name -> step(PipelineStage.PHONE, PipelineStatus.RUNNING, "${input.busy.recoveringPercent} %")
            errors[Step.RECOVER] != null -> step(PipelineStage.PHONE, PipelineStatus.ERROR, "Erreur", errors[Step.RECOVER])
            r.onBox -> step(PipelineStage.PHONE, PipelineStatus.TODO, "Sur le boîtier")
            else -> step(PipelineStage.PHONE, PipelineStatus.TODO, "Absent")
        }
        val vttError = task?.takeIf { !inSynthesis && (it.phase == TranscriptionPhase.FAILED || it.suspended) }
        val vtt = when {
            rank != null && !inSynthesis && (rank == 1 || task?.phase == TranscriptionPhase.CANCEL_REQUESTED) -> {
                val label = if (task?.phase == TranscriptionPhase.CANCEL_REQUESTED) "Annulation" else
                    task?.progress?.let { "${(it * 100).toInt().coerceIn(0, 100)} %" } ?: "Transcription"
                step(PipelineStage.VTT, PipelineStatus.RUNNING, label)
            }
            rank != null && !inSynthesis -> step(PipelineStage.VTT, PipelineStatus.WAITING, "En attente", waitingReason)
            vttError != null -> step(PipelineStage.VTT, PipelineStatus.ERROR, "Erreur", vttError.suspensionReason ?: vttError.error ?: "Transcription impossible.")
            errors[Step.TRANSCRIBE] != null && !hasVtt -> step(PipelineStage.VTT, PipelineStatus.ERROR, "Erreur", errors[Step.TRANSCRIBE])
            hasVtt -> step(PipelineStage.VTT, PipelineStatus.DONE, "Fait")
            else -> step(PipelineStage.VTT, PipelineStatus.TODO, "À faire")
        }
        val mdSuspended = synthesis?.phase == SynthesisPhase.SUSPENDED && input.synthesisConfigured
        val md = when {
            rank != null && inSynthesis && rank == 1 -> {
                val attempt = synthesis?.attempts?.coerceAtLeast(1) ?: 1
                val reason = synthesis?.nextAttemptAtMillis?.let {
                    "Nouvelle tentative à ${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))}"
                }
                step(PipelineStage.MD, PipelineStatus.RUNNING, "Synthèse $attempt/3", reason)
            }
            rank != null && inSynthesis -> step(PipelineStage.MD, PipelineStatus.WAITING, "En attente", waitingReason)
            mdSuspended -> step(PipelineStage.MD, PipelineStatus.ERROR, "Suspendue", synthesis?.suspensionReason ?: task?.suspensionReason ?: "Synthèse suspendue.")
            hasMd && input.provenance == ProvenanceStatus.STALE -> step(PipelineStage.MD, PipelineStatus.TODO, "Ancien VTT", "Cette synthèse utilise l'ancien VTT.")
            hasMd -> step(PipelineStage.MD, PipelineStatus.DONE, "Fait")
            !input.synthesisConfigured -> step(PipelineStage.MD, PipelineStatus.TODO, "À configurer", "Configure la synthèse dans les réglages.")
            else -> step(PipelineStage.MD, PipelineStatus.TODO, "À faire")
        }
        val drive = when {
            input.busy.sending == r.name -> step(PipelineStage.DRIVE, PipelineStatus.RUNNING, "Envoi")
            errors[Step.SEND] != null -> step(PipelineStage.DRIVE, PipelineStatus.ERROR, "Erreur", errors[Step.SEND])
            input.local.driveEnabled && input.drive != null && r.phoneFiles.isNotEmpty() && Recordings.missingInDrive(r, input.drive).isEmpty() ->
                step(PipelineStage.DRIVE, PipelineStatus.DONE, "Sur Drive")
            !input.local.driveEnabled -> step(PipelineStage.DRIVE, PipelineStatus.TODO, "Désactivé")
            else -> step(PipelineStage.DRIVE, PipelineStatus.TODO, "À faire")
        }
        val steps = listOf(phone, vtt, md, drive)
        val reason = steps.firstOrNull { it.reason != null }?.let { PipelineReason(it.stage, it.reason!!) }
        val button = when {
            rank != null && task?.phase == TranscriptionPhase.CANCEL_REQUESTED ->
                button(PipelineAction.CANCEL_VTT, "Annulation en cours", enabled = false, secondary = true)
            rank == 1 && inSynthesis -> button(PipelineAction.CANCEL_MD, "Annuler la synthèse", secondary = true)
            rank == 1 -> button(PipelineAction.CANCEL_VTT, "Annuler la transcription", secondary = true)
            rank != null -> button(PipelineAction.REMOVE_FROM_QUEUE, "Retirer de la file", secondary = true)
            input.busy.recovering == r.name -> null
            errors[Step.RECOVER] != null -> button(PipelineAction.RETRY_RECOVERY, "Réessayer", input.connected)
            !hasMp3 && r.onBox -> button(PipelineAction.RECOVER, "Récupérer", input.connected)
            !hasMp3 -> null
            vtt.status == PipelineStatus.ERROR -> button(PipelineAction.RETRY_VTT, "Réessayer le VTT")
            !hasVtt && !hasMd -> button(PipelineAction.CREATE_VTT_AND_MD, "Créer le VTT et le MD")
            !hasVtt -> button(PipelineAction.CREATE_VTT, "Créer le VTT")
            mdSuspended -> button(PipelineAction.RETRY_MD, "Relancer le MD")
            !hasMd && !input.synthesisConfigured -> button(PipelineAction.OPEN_SETTINGS, "Ouvrir les réglages")
            !hasMd -> button(PipelineAction.CREATE_MD, "Créer le MD")
            input.provenance == ProvenanceStatus.STALE -> button(PipelineAction.REDO_MD, "Relancer le MD")
            errors[Step.SEND] != null && input.local.driveEnabled -> button(PipelineAction.RETRY_SEND, "Réessayer l'envoi")
            input.local.driveEnabled && drive.status == PipelineStatus.TODO -> button(PipelineAction.SEND, "Envoyer sur Drive")
            else -> null
        }
        val badge = when {
            steps.any { it.status == PipelineStatus.ERROR } -> "Erreur"
            phone.status == PipelineStatus.RUNNING -> "Récupération ${input.busy.recoveringPercent} %"
            vtt.status == PipelineStatus.RUNNING -> if (vtt.label.endsWith("%")) "Transcription ${vtt.label}" else "Transcription…"
            md.status == PipelineStatus.RUNNING -> "Synthèse…"
            drive.status == PipelineStatus.RUNNING -> "Envoi…"
            steps.any { it.status == PipelineStatus.WAITING } -> "En attente"
            drive.status == PipelineStatus.DONE -> "Sur Drive"
            md.status == PipelineStatus.DONE -> "MD"
            vtt.status == PipelineStatus.DONE -> "VTT"
            phone.status == PipelineStatus.DONE -> "MP3"
            else -> "Sur le boîtier"
        }
        return PipelinePresentation(steps, reason, button, badge, rank)
    }

    private fun step(stage: PipelineStage, status: PipelineStatus, label: String, reason: String? = null) = PipelineStep(stage, status, label, reason)
    private fun button(action: PipelineAction, label: String, enabled: Boolean = true, secondary: Boolean = false) =
        PipelineButton(action, label, enabled, secondary)
    private fun ordinal(rank: Int) = if (rank == 1) "1er" else "${rank}e"
}
