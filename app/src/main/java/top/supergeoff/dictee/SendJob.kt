package top.supergeoff.dictee

/** Résultat de l'envoi d'un fichier : le nom à 14 chiffres de l'enregistrement et le nom du fichier du téléphone. */
sealed class SendResult {
    abstract val name: String
    abstract val fileName: String

    /** Fichier déposé dans le dossier Drive, avec la taille attendue. */
    data class Sent(override val name: String, override val fileName: String) : SendResult()

    /** Fichier déjà présent dans le dossier Drive : il n'a pas été envoyé. */
    data class AlreadyThere(override val name: String, override val fileName: String) : SendResult()

    /** Envoi du fichier échoué, avec la raison affichée sur la ligne de l'enregistrement. */
    data class Failed(override val name: String, override val fileName: String, val reason: String) : SendResult()
}

/** Fin d'un envoi. [results] suit l'ordre des fichiers traités. */
sealed class SendOutcome {
    abstract val results: List<SendResult>

    /** Tous les fichiers ont été traités, avec ou sans échec. */
    data class Done(override val results: List<SendResult>) : SendOutcome()

    /** L'envoi a été arrêté pour [reason] : [results] ne compte que les fichiers traités avant l'arrêt. */
    data class Stopped(val reason: String, override val results: List<SendResult>) : SendOutcome()
}

/** Dépose dans un dossier Drive les fichiers du téléphone qui y manquent. */
class SendJob(
    private val drive: DriveClient,
    private val store: PhoneStore,
    private val log: Log,
) {

    /** Fichier du téléphone à traiter. [upload] est vrai quand il manque dans le dossier Drive. */
    private class Item(val name: String, val kind: FileKind, val file: PhoneFile, val upload: Boolean)

    /**
     * Lit une fois le contenu du dossier, puis envoie les fichiers absents de [recordings], dans l'ordre de la liste
     * et, pour chaque enregistrement, dans l'ordre MP3, VTT, MD. Les fichiers déjà présents ne sont pas envoyés :
     * une sélection explicite ([explicitSelection]) écrit une ligne au journal par fichier présent, sinon une seule ligne les compte.
     * [onProgress] reçoit le nom de l'enregistrement avec l'avancement : 0 % au départ, puis le pourcentage envoyé,
     * puis 100 % après le contrôle de la taille. Rien n'est signalé pour un fichier déjà présent.
     * [onResult] reçoit chaque résultat, dans l'ordre.
     * Un accès Google à renouveler arrête l'envoi. Toute autre erreur sur un fichier le marque en échec, et l'envoi continue.
     */
    fun run(
        folder: DriveFolder,
        recordings: List<Recording>,
        onProgress: (name: String, progress: Progress) -> Unit,
        onResult: (SendResult) -> Unit,
        explicitSelection: Boolean = false,
    ): SendOutcome {
        val listing = try {
            DriveListing(drive.listFiles(folder.id))
        } catch (e: Exception) {
            return stop(if (needsRenewal(e)) DriveClient.RENEW_MESSAGE else "Dossier Drive inaccessible : ${reasonOf(e)}", emptyList())
        }
        val items = recordings.flatMap { recording ->
            val missing = Recordings.missingInDrive(recording, listing)
            recording.phoneFiles.entries.sortedBy { it.key }.map { (kind, file) -> Item(recording.name, kind, file, file in missing) }
        }
        val total = items.count { it.upload }
        val present = items.size - total
        if (!explicitSelection && present > 0) log.add(Source.DRIVE, alreadyThere(present, folder))
        val results = mutableListOf<SendResult>()
        var index = 0
        for (item in items) {
            val result = if (item.upload) {
                index++
                try {
                    send(folder, item, index, total, onProgress)
                } catch (e: Exception) {
                    if (needsRenewal(e)) return stop(DriveClient.RENEW_MESSAGE, results)
                    failed(item, reasonOf(e), (e as? PhoneStoreException)?.journalText)
                }
            } else {
                if (explicitSelection) log.add(Source.DRIVE, "${item.file.fileName} déjà dans ${folder.name}, non envoyé")
                SendResult.AlreadyThere(item.name, item.file.fileName)
            }
            results += result
            onResult(result)
        }
        return SendOutcome.Done(results)
    }

    /** Envoie un fichier, puis compare la taille enregistrée par Drive à celle du téléphone. */
    private fun send(
        folder: DriveFolder,
        item: Item,
        index: Int,
        total: Int,
        onProgress: (name: String, progress: Progress) -> Unit,
    ): SendResult {
        fun progress(percent: Int) = onProgress(item.name, Progress(ProgressKind.SEND, index, total, percent))
        val fileName = item.file.fileName
        val length = item.file.size
        progress(0)
        var shown = 0
        val stored = drive.upload(
            folderId = folder.id,
            fileName = fileName,
            mime = item.kind.mime,
            length = length,
            open = { store.open(fileName) },
            onProgress = { sent ->
                val percent = if (length <= 0) 0 else (sent * 100 / length).coerceAtMost(100).toInt()
                if (percent != shown) {
                    shown = percent
                    progress(percent)
                }
            },
        )
        if (stored != length) return failed(item, "taille Drive $stored au lieu de $length")
        progress(100)
        log.add(Source.DRIVE, "$fileName déposé dans ${folder.name}")
        return SendResult.Sent(item.name, fileName)
    }

    /** La ligne qui compte les fichiers déjà présents, par exemple « 2 fichiers déjà dans Dictée, non envoyés ». */
    private fun alreadyThere(count: Int, folder: DriveFolder): String =
        if (count == 1) "1 fichier déjà dans ${folder.name}, non envoyé" else "$count fichiers déjà dans ${folder.name}, non envoyés"

    /** [logged] est le texte du journal quand il diffère de [reason], celui de la ligne de l'enregistrement : il ajoute la cause technique. */
    private fun failed(item: Item, reason: String, logged: String? = null): SendResult.Failed {
        log.error(Source.DRIVE, "${item.file.fileName} : ${logged ?: reason}")
        return SendResult.Failed(item.name, item.file.fileName, reason)
    }

    private fun stop(reason: String, results: List<SendResult>): SendOutcome.Stopped {
        log.error(Source.DRIVE, reason)
        return SendOutcome.Stopped(reason, results)
    }

    /** Vrai pour une réponse 401 de Drive : le client a déjà refait l'appel avec un jeton neuf. */
    private fun needsRenewal(e: Exception): Boolean = e is DriveException && e.code == UNAUTHORIZED

    private fun reasonOf(e: Exception): String = e.message ?: e.javaClass.simpleName

    private companion object {
        const val UNAUTHORIZED = 401
    }
}

/**
 * Dossier Drive de l'envoi, avec l'état à enregistrer. Un dossier choisi est contrôlé avec [DriveClient.checkFolder] :
 * une erreur remonte telle quelle, car l'app ne remplace jamais le dossier choisi.
 * Sans dossier choisi, l'app prend « Dictée » parmi les dossiers connus s'il passe le contrôle, sinon le cherche dans Drive,
 * sinon le crée à la racine. Le dossier trouvé ou créé rejoint les dossiers connus avec l'origine [FolderOrigin.CREATED]
 * et devient le dossier choisi.
 */
fun resolveFolder(state: LocalState, drive: DriveClient): Pair<DriveFolder, LocalState> {
    val chosenId = state.chosenFolderId
    if (chosenId != null) {
        val folder = drive.checkFolder(chosenId)
        return folder to state.withChosenFolder(folder, FolderOrigin.PICKED)
    }
    val known = state.knownFolders
        .filter { it.name == DriveClient.DEFAULT_FOLDER }
        .firstNotNullOfOrNull { runCatching { drive.checkFolder(it.id) }.getOrNull() }
    val folder = known ?: drive.findFolder(DriveClient.DEFAULT_FOLDER) ?: drive.createFolder(DriveClient.DEFAULT_FOLDER)
    return folder to state.withChosenFolder(folder, FolderOrigin.CREATED)
}

/**
 * Fait de [folder] le dossier choisi. Il rejoint les dossiers connus avec [origin] ;
 * s'il y est déjà, seul son nom est mis à jour et son origine reste celle qu'il avait.
 */
private fun LocalState.withChosenFolder(folder: DriveFolder, origin: FolderOrigin): LocalState {
    val folders = if (knownFolders.any { it.id == folder.id }) {
        knownFolders.map { if (it.id == folder.id) it.copy(name = folder.name) else it }
    } else {
        knownFolders + KnownFolder(folder.id, folder.name, origin)
    }
    return copy(knownFolders = folders, chosenFolderId = folder.id)
}
