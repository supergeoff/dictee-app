package top.supergeoff.dictee

/** « Supprimer partout » efface chaque niveau ; « Libérer l'espace » garde Drive et ne libère le téléphone que si Drive confirme les documents. */
enum class DeletionMode { EVERYWHERE, FREE_SPACE }

/** Niveaux à effacer pour un enregistrement. [driveFiles] est vide quand Drive n'est pas visé. */
data class DeletionTarget(val name: String, val box: Boolean, val phone: Boolean, val driveFiles: List<DriveFile>)

data class UnifiedDeletionPlan(
    val mode: DeletionMode,
    val targets: List<DeletionTarget>,
    val blockedNames: Set<String>,
    /** Vrai quand Drive est désactivé alors qu'un fichier Drive d'une cible existait : la confirmation le dit. */
    val driveSkipped: Boolean,
    val boxAddress: String?,
    val folderUri: String?,
)

/** Fichiers de la liste Drive qui appartiennent à l'enregistrement [name], y compris un MP3 de la version 0.1.5. */
fun driveFilesOf(name: String, drive: DriveListing?): List<DriveFile> =
    drive?.entries.orEmpty().filter { RecordName.parse(it.name)?.name == name || RecordName.legacyMp3(it.name) == name }

object UnifiedDeletion {
    fun plan(
        mode: DeletionMode, recordings: List<Recording>, selected: Set<String>, protectedNames: Set<String>,
        drive: DriveListing?, local: LocalState, boxAddress: String?, folderUri: String?,
    ): UnifiedDeletionPlan {
        val destination = local.driveAccount?.let { account -> local.chosenFolderId?.let { DriveDestination(account, it) } }
        var driveSkipped = false
        val targets = recordings.filter { it.name in selected }.distinctBy { it.name }.mapNotNull { recording ->
            val hasPhone = recording.phoneFiles.isNotEmpty()
            when (mode) {
                DeletionMode.EVERYWHERE -> {
                    val listed = driveFilesOf(recording.name, drive)
                    if (!local.driveEnabled && listed.isNotEmpty()) driveSkipped = true
                    DeletionTarget(recording.name, recording.onBox, hasPhone, if (local.driveEnabled) listed else emptyList())
                }
                DeletionMode.FREE_SPACE -> {
                    val driveOk = destination != null && drive != null && listOf(FileKind.VTT, FileKind.MD).all { kind ->
                        DriveSync.hasConfirmedCopy(recording.name, kind, destination, drive.entries, local.driveReceipts)
                    }
                    when {
                        driveOk -> DeletionTarget(recording.name, recording.onBox, hasPhone, emptyList())
                        hasPhone -> DeletionTarget(recording.name, recording.onBox, false, emptyList())
                        else -> null
                    }
                }
            }
        }.filter { it.box || it.phone || it.driveFiles.isNotEmpty() }
        return UnifiedDeletionPlan(
            mode, targets, targets.map { it.name }.toSet() intersect protectedNames, driveSkipped, boxAddress, folderUri,
        )
    }
}
