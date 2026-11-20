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
    /** Vrai quand « Supprimer partout » vise Drive (activé, dossier choisi) sans en avoir la liste : ses copies ne seront pas touchées. */
    val driveUnreadable: Boolean = false,
    /** Vrai quand le dossier du téléphone est choisi mais illisible : ses fichiers ne seront pas touchés. */
    val phoneUnreadable: Boolean = false,
    /** Titre ou date de chaque cible, par nom d'enregistrement. */
    val labels: Map<String, String> = emptyMap(),
    /** « Libérer l'espace » seulement : cibles dont le MP3 ne resterait ni sur le téléphone ni sur Drive une fois effacées. */
    val mp3LostNames: Set<String> = emptySet(),
)

/** Plan réduit à ce qui reste valide au moment d'exécuter, et les niveaux retirés (« nom : niveau »). */
data class RevalidatedDeletion(val plan: UnifiedDeletionPlan, val dropped: List<String>)

/** Fichiers de la liste Drive qui appartiennent à l'enregistrement [name], y compris un MP3 de la version 0.1.5. */
fun driveFilesOf(name: String, drive: DriveListing?): List<DriveFile> =
    drive?.entries.orEmpty().filter { RecordName.parse(it.name)?.name == name || RecordName.legacyMp3(it.name) == name }

object UnifiedDeletion {
    /**
     * [hashes] sont les empreintes actuelles des VTT et MD du téléphone. « Libérer l'espace » ne touche jamais un enregistrement
     * dont la récupération est en erreur. Il n'efface le téléphone que si Drive prouve les documents pour l'empreinte actuelle,
     * et le boîtier que si un MP3 complet existe sur le téléphone ou sur Drive. [phoneReadable] est faux quand le dossier choisi est illisible.
     */
    fun plan(
        mode: DeletionMode, recordings: List<Recording>, selected: Set<String>, protectedNames: Set<String>,
        drive: DriveListing?, local: LocalState, boxAddress: String?, folderUri: String?,
        hashes: Map<String, String>, phoneReadable: Boolean = true,
    ): UnifiedDeletionPlan {
        val destination = local.driveAccount?.let { account -> local.chosenFolderId?.let { DriveDestination(account, it) } }
        var driveSkipped = false
        val mp3Lost = mutableSetOf<String>()
        val targets = recordings.filter { it.name in selected }.distinctBy { it.name }.mapNotNull { recording ->
            val hasPhone = recording.phoneFiles.isNotEmpty()
            when (mode) {
                DeletionMode.EVERYWHERE -> {
                    val listed = driveFilesOf(recording.name, drive)
                    if (!local.driveEnabled && listed.isNotEmpty()) driveSkipped = true
                    DeletionTarget(recording.name, recording.onBox, hasPhone, if (local.driveEnabled) listed else emptyList())
                }
                DeletionMode.FREE_SPACE -> {
                    if (Step.RECOVER in local.errors[recording.name].orEmpty()) return@mapNotNull null
                    val proof = if (local.driveEnabled && drive != null && destination != null) drive.entries to destination else null
                    val documentsProven = proof != null && listOf(FileKind.VTT, FileKind.MD).all { kind ->
                        val file = recording.phoneFiles[kind]
                        if (file == null) {
                            DriveSync.hasConfirmedCopy(recording.name, kind, proof.second, proof.first, local.driveReceipts)
                        } else {
                            hashes[file.fileName]?.let {
                                DriveSync.action(file.fileName, kind, it, proof.second, proof.first, local.driveReceipts) == DriveWriteAction.SKIP
                            } == true
                        }
                    }
                    val mp3OnDrive = proof != null && DriveSync.action(
                        RecordName.fileName(recording.type, recording.name, FileKind.MP3), FileKind.MP3, null,
                        proof.second, proof.first, local.driveReceipts,
                    ) == DriveWriteAction.SKIP
                    val completeMp3 = FileKind.MP3 in recording.phoneFiles || mp3OnDrive
                    val box = recording.onBox && completeMp3
                    val phone = hasPhone && documentsProven
                    if (box && phone && !mp3OnDrive) mp3Lost += recording.name
                    DeletionTarget(recording.name, box, phone, emptyList())
                }
            }
        }.filter { it.box || it.phone || it.driveFiles.isNotEmpty() }
        val labels = recordings.filter { recording -> targets.any { it.name == recording.name } }.associate {
            it.name to (local.recordingSubjects[it.name] ?: RecordName.rowTitle(it.type, it.name))
        }
        return UnifiedDeletionPlan(
            mode, targets, targets.map { it.name }.toSet() intersect protectedNames, driveSkipped, boxAddress, folderUri,
            driveUnreadable = mode == DeletionMode.EVERYWHERE && local.driveEnabled && local.chosenFolderId != null && drive == null && targets.isNotEmpty(),
            phoneUnreadable = !phoneReadable && targets.isNotEmpty(),
            labels = labels,
            mp3LostNames = mp3Lost,
        )
    }

    /** Réduit le plan [confirmed] à l'intersection avec le plan [fresh] recalculé sous le verrou : rien n'est jamais ajouté. */
    fun restrict(confirmed: UnifiedDeletionPlan, fresh: UnifiedDeletionPlan): RevalidatedDeletion {
        val freshByName = fresh.targets.associateBy { it.name }
        val dropped = mutableListOf<String>()
        val targets = confirmed.targets.mapNotNull { target ->
            val current = freshByName[target.name]
            val box = target.box && current?.box == true
            val phone = target.phone && current?.phone == true
            val freshIds = current?.driveFiles.orEmpty().map { it.id }.toSet()
            val driveFiles = target.driveFiles.filter { it.id in freshIds }
            if (target.box && !box) dropped += "${target.name} : boîtier"
            if (target.phone && !phone) dropped += "${target.name} : téléphone"
            if (driveFiles.size != target.driveFiles.size) dropped += "${target.name} : Drive"
            DeletionTarget(target.name, box, phone, driveFiles).takeIf { it.box || it.phone || it.driveFiles.isNotEmpty() }
        }
        val mp3LostNames = confirmed.mp3LostNames.filter { name -> targets.any { it.name == name && it.box && it.phone } }.toSet()
        return RevalidatedDeletion(confirmed.copy(targets = targets, mp3LostNames = mp3LostNames), dropped)
    }
}
