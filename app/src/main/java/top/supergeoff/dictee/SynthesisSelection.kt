package top.supergeoff.dictee

enum class RequestedWork { MISSING, REDO_VTT_AND_MD, REDO_MD }

data class FilePlan(
    val recording: Recording,
    val makeVtt: Boolean,
    val makeMd: Boolean,
    val replaceVtt: Boolean,
    val replaceMd: Boolean,
)

object SynthesisSelection {
    fun plan(
        recordings: List<Recording>, selectedNames: Set<String>?, errors: Map<String, Map<Step, String>>,
        activeNames: Set<String>, request: RequestedWork,
    ): Pair<List<FilePlan>, Map<String, TranscriptionExclusion>> {
        require(request == RequestedWork.MISSING || selectedNames?.size == 1) {
            "Une reprise explicite porte sur un seul enregistrement sélectionné."
        }
        val plans = mutableListOf<FilePlan>()
        val excluded = linkedMapOf<String, TranscriptionExclusion>()
        for (recording in recordings.sortedBy { it.name }) {
            if (selectedNames != null && recording.name !in selectedNames) continue
            val hasVtt = FileKind.VTT in recording.phoneFiles
            val hasMd = FileKind.MD in recording.phoneFiles
            if (request == RequestedWork.MISSING && hasVtt && hasMd) continue
            when {
                recording.name in activeNames -> excluded[recording.name] = TranscriptionExclusion.ALREADY_ACTIVE
                FileKind.MP3 !in recording.phoneFiles -> excluded[recording.name] = TranscriptionExclusion.MISSING_MP3
                Step.RECOVER in errors[recording.name].orEmpty() -> excluded[recording.name] = TranscriptionExclusion.RECOVERY_FAILED
                request == RequestedWork.REDO_MD && !hasVtt -> excluded[recording.name] = TranscriptionExclusion.MISSING_VTT
                else -> plans += when (request) {
                    RequestedWork.MISSING -> FilePlan(recording, !hasVtt, !hasMd, false, false)
                    RequestedWork.REDO_VTT_AND_MD -> FilePlan(recording, true, true, true, true)
                    RequestedWork.REDO_MD -> FilePlan(recording, false, true, false, true)
                }
            }
        }
        selectedNames?.minus(recordings.map { it.name }.toSet())?.sorted()?.forEach {
            excluded[it] = TranscriptionExclusion.MISSING_MP3
        }
        return plans to excluded
    }
}

internal fun synthesisSelectionMessage(
    recordings: List<Recording>, selectedNames: Set<String>?, errors: Map<String, Map<Step, String>>, activeNames: Set<String>,
): String? {
    if (selectedNames == null) return if (Recordings.toTranscribe(recordings, null, errors, activeNames).candidates.isEmpty())
        "Aucun VTT ou MD à créer pour les enregistrements exploitables." else null
    val excluded = Recordings.toTranscribe(recordings, selectedNames, errors, activeNames).excluded
    val missing = excluded.filterValues { it == TranscriptionExclusion.MISSING_MP3 || it == TranscriptionExclusion.RECOVERY_FAILED }.keys
    val active = selectedNames.intersect(activeNames)
    val complete = recordings.filter { it.name in selectedNames && it.name !in active &&
        FileKind.VTT in it.phoneFiles && FileKind.MD in it.phoneFiles }.map { it.name }
    val nothingToDo = Recordings.toTranscribe(recordings, selectedNames, errors, activeNames).candidates.isEmpty()
    return listOfNotNull(
        missing.takeIf { it.isNotEmpty() }?.let { "Sans MP3 exploitable sur le téléphone : ${it.sorted().joinToString(", ")}." },
        active.takeIf { it.isNotEmpty() }?.let { "Déjà en cours : ${it.sorted().joinToString(", ")}." },
        complete.takeIf { nothingToDo && it.isNotEmpty() }?.let { "VTT et MD déjà présents : ${it.sorted().joinToString(", ")}." },
    ).joinToString(" ").ifEmpty { null }
}
