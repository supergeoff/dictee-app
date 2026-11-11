package top.supergeoff.dictee

import java.io.File

/** Résultat de la récupération d'un enregistrement, identifié par son nom à 14 chiffres. */
sealed class RecoveryResult {
    abstract val name: String

    /** `fileName` est le MP3 écrit dans le dossier du téléphone, préfixé par le type (`Call-AAAAMMJJHHMMSS.mp3`). */
    data class Ok(override val name: String, val fileName: String, val size: Long) : RecoveryResult()

    /** `stopsQueue` : la récupération s'arrête après cet échec. */
    data class Failed(override val name: String, val reason: String, val stopsQueue: Boolean) : RecoveryResult()
}

/**
 * Récupère des enregistrements du boîtier avec une [V05Session] déjà passée par la poignée de main,
 * puis les écrit dans le dossier du téléphone. [cacheDir] appartient à la seule récupération.
 */
class RecoveryJob(
    private val session: V05Session,
    private val store: PhoneStore,
    private val cacheDir: File,
    private val log: Log,
) {

    /**
     * Vide [cacheDir], puis récupère les [entries] de la plus ancienne à la plus récente.
     * [onProgress] reçoit le nom en cours avec son avancement : 0 % au départ, puis les octets reçus.
     * [onResult] reçoit chaque résultat, dans l'ordre. Un échec qui arrête la file termine la récupération.
     * Renvoie les résultats des entrées traitées.
     */
    suspend fun run(
        entries: List<ListItem.Entry>,
        onProgress: (name: String, progress: Progress) -> Unit,
        onResult: suspend (RecoveryResult) -> Unit,
        beforeRecovery: suspend (ListItem.Entry) -> Unit = {},
    ): List<RecoveryResult> {
        cacheDir.mkdirs()
        cacheDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        val queue = entries.sortedBy { it.name }   // 14 chiffres : l'ordre des noms est l'ordre chronologique
        val results = mutableListOf<RecoveryResult>()
        for ((i, entry) in queue.withIndex()) {
            fun progress(percent: Int) = Progress(ProgressKind.RECOVERY, i + 1, queue.size, percent)
            beforeRecovery(entry)
            onProgress(entry.name, progress(0))
            val result = recover(entry) { received, expected -> onProgress(entry.name, progress(percentOf(received, expected))) }
            results += result
            onResult(result)
            if (result is RecoveryResult.Failed && result.stopsQueue) break
        }
        return results
    }

    private suspend fun recover(entry: ListItem.Entry, onBytes: (received: Long, expected: Long) -> Unit): RecoveryResult =
        when (val download = session.download(entry.name, entry.size, cacheDir, onBytes)) {
            is Download.Failed -> RecoveryResult.Failed(entry.name, download.reason, stopsQueue = download.fatal)
            is Download.Ok -> write(entry, download)
        }

    /** Écrit le MP3 dans le dossier du téléphone sous son nom préfixé, puis supprime le fichier du cache. */
    private fun write(entry: ListItem.Entry, download: Download.Ok): RecoveryResult {
        val fileName = RecordName.fileName(typeOf(entry), entry.name, FileKind.MP3)
        try {
            store.write(fileName, download.file)
            log.add(Source.FS, "$fileName écrit dans le dossier du téléphone")
            return RecoveryResult.Ok(entry.name, fileName, download.size)
        } catch (e: PhoneStoreException) {
            log.error(Source.FS, e.journalText)   // la cause technique va au journal seul : la ligne de l'enregistrement garde le message
            return RecoveryResult.Failed(entry.name, e.message ?: e.javaClass.simpleName, stopsQueue = true)
        } finally {
            download.file.delete()
        }
    }

    /** Type tiré de l'indicateur de la liste. Un indicateur inconnu ou absent donne Note, avec une ligne au journal. */
    private fun typeOf(entry: ListItem.Entry): RecordType =
        RecordType.fromFlag(entry.flag) ?: RecordType.NOTE.also {
            val flag = if (entry.flag < 0) "absent" else "%02x inconnu".format(entry.flag)
            log.add(Source.FS, "${entry.name} : indicateur $flag, type Note retenu")
        }

    /** Pourcentage reçu, de 0 à 100. Zéro quand la taille attendue est nulle. */
    private fun percentOf(received: Long, expected: Long): Int =
        if (expected <= 0) 0 else (received * 100 / expected).coerceAtMost(100).toInt()
}
