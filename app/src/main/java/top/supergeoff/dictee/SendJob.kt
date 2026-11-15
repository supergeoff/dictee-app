package top.supergeoff.dictee

import java.io.File

/** Résultat de l'envoi d'un fichier : le nom à 14 chiffres de l'enregistrement et le nom du fichier du téléphone. */
sealed class SendResult {
    abstract val name: String
    abstract val fileName: String

    /** Fichier déposé dans le dossier Drive, avec la taille attendue. */
    data class Sent(override val name: String, override val fileName: String, val receipt: DriveReceipt? = null) : SendResult()

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
    private val cacheDir: File,
) {

    private class Item(val name: String, val kind: FileKind, val file: PhoneFile)
    private class Prepared(val item: Item, val snapshot: DriveUploadSnapshot?, val action: DriveWriteAction?, val error: Exception?)

    /** Prépare les copies des documents avant décision ; le résultat est livré avant le fichier suivant. */
    suspend fun run(
        folder: DriveFolder,
        recordings: List<Recording>,
        choice: DriveSendChoice,
        destination: DriveDestination,
        receipts: List<DriveReceipt>,
        onProgress: (name: String, progress: Progress) -> Unit,
        onResult: suspend (SendResult) -> Unit,
        explicitSelection: Boolean = false,
    ): SendOutcome {
        require(destination.account.isNotBlank() && destination.folderId == folder.id)
        val listing = try {
            drive.listEntries(folder.id)
        } catch (e: Exception) {
            return stop(if (needsRenewal(e)) DriveClient.RENEW_MESSAGE else "Dossier Drive inaccessible : ${reasonOf(e)}", emptyList())
        }
        val items = recordings.flatMap { recording ->
            recording.phoneFiles.entries.filter { it.key in DriveSync.requiredKinds(choice) }
                .sortedBy { it.key }.map { (kind, file) -> Item(recording.name, kind, file) }
        }
        val prepared = mutableListOf<Prepared>()
        try {
            for (item in items) {
                var snapshot: DriveUploadSnapshot? = null
                try {
                    if (item.kind != FileKind.MP3) snapshot = DriveUploadSnapshot.capture(store, item.file.fileName, cacheDir)
                    val action = DriveSync.action(item.file.fileName, item.kind, snapshot?.sha256, destination, listing, receipts)
                    prepared += Prepared(item, snapshot, action, null)
                } catch (e: Exception) {
                    snapshot?.close()
                    prepared += Prepared(item, null, null, e)
                }
            }
            val total = prepared.count { it.action == DriveWriteAction.CREATE || it.action == DriveWriteAction.UPDATE }
            val present = prepared.count { it.action == DriveWriteAction.SKIP }
            if (!explicitSelection && present > 0) log.add(Source.DRIVE, alreadyThere(present, folder))
            val results = mutableListOf<SendResult>()
            var index = 0
            for (entry in prepared) {
                val item = entry.item
                val result = when {
                    entry.error != null -> failed(item, reasonOf(entry.error), (entry.error as? PhoneStoreException)?.journalText)
                    entry.action == DriveWriteAction.CONFLICT -> failed(item, "Plusieurs fichiers portent ce nom dans Drive")
                    entry.action == DriveWriteAction.SKIP -> {
                        if (explicitSelection) log.add(Source.DRIVE, "${item.file.fileName} déjà dans ${folder.name}, non envoyé")
                        SendResult.AlreadyThere(item.name, item.file.fileName)
                    }
                    else -> {
                        index++
                        try {
                            val existing = if (entry.action == DriveWriteAction.UPDATE) listing.single { it.name == item.file.fileName }.id else null
                            send(folder, destination, item, entry.snapshot, existing, index, total, onProgress)
                        } catch (e: Exception) {
                            if (needsRenewal(e)) return stop(DriveClient.RENEW_MESSAGE, results)
                            failed(item, reasonOf(e), (e as? PhoneStoreException)?.journalText)
                        }
                    }
                }
                results += result
                onResult(result)
            }
            return SendOutcome.Done(results)
        } finally {
            prepared.forEach { it.snapshot?.close() }
        }
    }

    private fun send(
        folder: DriveFolder, destination: DriveDestination, item: Item, snapshot: DriveUploadSnapshot?, existing: String?,
        index: Int, total: Int, onProgress: (name: String, progress: Progress) -> Unit,
    ): SendResult {
        fun progress(percent: Int) = onProgress(item.name, Progress(ProgressKind.SEND, index, total, percent))
        val fileName = item.file.fileName
        val length = snapshot?.size ?: item.file.size
        progress(0)
        var shown = 0
        val stored = drive.storeFile(
            folderId = folder.id, fileName = fileName, mime = item.kind.mime, length = length,
            open = { snapshot?.open() ?: store.open(fileName) }, existingFileId = existing,
            onProgress = { sent ->
                val percent = if (length <= 0) 0 else (sent * 100 / length).coerceAtMost(100).toInt()
                if (percent != shown) { shown = percent; progress(percent) }
            },
        )
        if (stored.size != length) return failed(item, "taille Drive ${stored.size} au lieu de $length")
        progress(100)
        log.add(Source.DRIVE, "$fileName déposé dans ${folder.name}")
        val receipt = snapshot?.let { DriveReceipt(destination, fileName, stored.id, it.sha256) }
        return SendResult.Sent(item.name, fileName, receipt)
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
