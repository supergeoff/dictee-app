package top.supergeoff.dictee

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Dossier du téléphone choisi par l'utilisateur, lu et écrit par le Storage Access Framework.
 * Toute erreur d'accès au dossier sort en [PhoneStoreException].
 */
class PhoneFolder(context: Context, val treeUri: Uri) : PhoneStore {
    private val resolver = context.contentResolver

    /** Document du dossier : identifiant, nom affiché et taille (0 si inconnue). */
    private class Child(val documentId: String, val name: String, val size: Long)

    override fun list(): List<PhoneFile> = guarded {
        children().filter { RecordName.parse(it.name) != null }.map { PhoneFile(it.name, it.size) }
    }

    override fun write(fileName: String, source: File) = guarded { writeDocument(fileName, source, true) { it() } }

    override fun writeGuarded(fileName: String, source: File, replace: Boolean, promote: ((() -> Unit) -> Unit)) =
        guarded { writeDocument(fileName, source, replace, promote) }

    override fun open(fileName: String): InputStream = guarded {
        val child = find(fileName) ?: throw PhoneStoreException("Fichier absent du dossier du téléphone : $fileName")
        resolver.openInputStream(documentUri(child)) ?: throw FileNotFoundException(fileName)
    }

    /** Adresse du fichier [fileName] dans le dossier, ou null s'il est absent ou si le dossier est inaccessible. */
    fun uriOf(fileName: String): Uri? =
        try {
            guarded { find(fileName)?.let { documentUri(it) } }
        } catch (e: PhoneStoreException) {
            null
        }

    /** Documents du dossier, en une seule requête. */
    private fun children(): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE)
        val cursor = resolver.query(uri, columns, null, null, null) ?: throw FileNotFoundException("dossier illisible")
        return cursor.use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    val name = rows.getString(NAME_COLUMN) ?: continue
                    add(Child(rows.getString(ID_COLUMN), name, if (rows.isNull(SIZE_COLUMN)) 0L else rows.getLong(SIZE_COLUMN)))
                }
            }
        }
    }

    private fun find(fileName: String): Child? = children().firstOrNull { it.name == fileName }

    private fun documentUri(child: Child): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId)

    /**
     * Écrit le fichier sous un nom provisoire `<nom>.part`, que [list] ignore, puis le renomme : aucun fichier tronqué ne reste sous le nom définitif.
     * Le `.part` a le type `application/octet-stream`, pour que le fournisseur n'ajoute pas d'extension à son nom, et le dossier doit
     * annoncer qu'il permet de renommer. Les restes d'un essai précédent sont traités d'abord ([clearLeftovers]) ; si un reste résiste,
     * l'écriture s'arrête. Un ancien document du nom définitif passe à `<nom>.old`, que [list] ignore aussi, et n'est supprimé qu'après le renommage.
     * Un échec avant le renommage final défait tout, y compris celui de la création du `.part` : l'ancien document reprend son nom et le `.part`
     * est supprimé. Si cette remise en place échoue aussi, l'ancien document reste sous `<nom>.old`.
     * Après le renommage final, un nom illisible (requête sans réponse, sans ligne ou sans nom) laisse le nouveau document en place et supprime l'ancien, puis l'erreur remonte ;
     * un autre nom lu rend son nom à l'ancien document et lève une [PhoneStoreException] : le document renommé reste en place,
     * ce n'est plus le fichier attendu.
     */
    private fun writeDocument(fileName: String, source: File, replace: Boolean, promote: ((() -> Unit) -> Unit)) {
        clearLeftovers(fileName)
        var part: Uri? = null
        var aside: Uri? = null
        var promoted: Uri? = null
        var called = false
        var active = true
        try {
            part = createPart(fileName + PART_SUFFIX)
            checkRename(part)
            copy(source, part)
            promote {
                check(active && !called) { "Promotion hors de la garde ou déjà exécutée" }
                called = true
                if (!replace) requireAbsent(fileName)
                if (replace) aside = moveAside(fileName)
                if (!replace) requireAbsent(fileName)
                promoted = DocumentsContract.renameDocument(resolver, part, fileName) ?: throw FileNotFoundException(fileName)
            }
            active = false
            if (!called || promoted == null) throw PhoneStoreException("Promotion de la synthèse refusée.")
        } catch (e: Exception) {
            active = false
            if (promoted == null) undo(part, aside, fileName) else deleteAside(aside)
            throw e
        }
        settle(promoted, aside, fileName)
    }

    private fun requireAbsent(fileName: String) {
        if (find(fileName) != null) throw PhoneStoreException("Le fichier « $fileName » existe déjà dans le dossier du téléphone.")
    }

    /**
     * Traite les restes d'un essai précédent. Un `<nom>.part` est supprimé ; s'il résiste, l'écriture s'arrête plutôt que de créer `<nom>.part (1)`.
     * Un `<nom>.old` est le reste d'une remise en place ratée : sans document final, il reprend le nom définitif, pour ne pas perdre l'ancien
     * contenu si cet essai échoue aussi (si le renommage échoue, il reste et l'écriture s'arrête) ; avec un document final, il est supprimé.
     */
    private fun clearLeftovers(fileName: String) {
        val partName = fileName + PART_SUFFIX
        val oldName = fileName + OLD_SUFFIX
        val all = children()
        val hasFinal = all.any { it.name == fileName }
        for (child in all.filter { it.name == partName || (hasFinal && it.name == oldName) }) {
            if (!DocumentsContract.deleteDocument(resolver, documentUri(child))) throw cannotDelete(child.name)
        }
        if (!hasFinal) all.firstOrNull { it.name == oldName }?.let { restoreLeftover(it, fileName) }
    }

    /** Rend le nom définitif à un `<nom>.old` resté seul. S'il ne se renomme pas, il reste en place et l'écriture s'arrête. */
    private fun restoreLeftover(old: Child, fileName: String) {
        val result = runCatching { DocumentsContract.renameDocument(resolver, documentUri(old), fileName) }
        if (result.getOrNull() == null) {
            throw PhoneStoreException("Le dossier du téléphone ne permet pas de rendre son nom à « ${old.name} ». Renomme-le à la main.", result.exceptionOrNull())
        }
    }

    private fun createPart(partName: String): Uri {
        val folder = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        return DocumentsContract.createDocument(resolver, folder, PART_MIME, partName) ?: throw FileNotFoundException(partName)
    }

    /**
     * Lance une [PhoneStoreException] si le fournisseur n'annonce pas `FLAG_SUPPORTS_RENAME` pour ce document, avant de toucher à l'ancien.
     * Une requête sans réponse ne dit rien du renommage : c'est une erreur d'accès, pas un dossier sans renommage.
     */
    private fun checkRename(document: Uri) {
        val flags = resolver.query(document, arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use { rows ->
            if (rows.moveToFirst()) rows.getInt(0) else null
        } ?: throw PhoneStoreException(INACCESSIBLE, FileNotFoundException(document.toString()))
        if ((flags and Document.FLAG_SUPPORTS_RENAME) == 0) throw PhoneStoreException(NO_RENAME)
    }

    /** Fait passer l'ancien document du nom [fileName] à `<nom>.old`. Null s'il n'y a pas d'ancien document. */
    private fun moveAside(fileName: String): Uri? {
        val old = find(fileName) ?: return null
        val oldName = fileName + OLD_SUFFIX
        return DocumentsContract.renameDocument(resolver, documentUri(old), oldName) ?: throw FileNotFoundException(oldName)
    }

    /** Rend son nom à l'ancien document et supprime le `.part`, quand ils existent. Chaque étape est tentée, aucune ne lève. */
    private fun undo(part: Uri?, aside: Uri?, fileName: String) {
        aside?.let { old -> runCatching { DocumentsContract.renameDocument(resolver, old, fileName) } }
        part?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
    }

    /**
     * Le `.part` est renommé : lit son nom pour s'en assurer. Un nom illisible ([displayName] lève) laisse le nouveau document en place,
     * supprime l'ancien et relance l'erreur de lecture ; un autre nom lu rend son nom à l'ancien et lève une [PhoneStoreException].
     */
    private fun settle(promoted: Uri, aside: Uri?, fileName: String) {
        val actualName = try {
            displayName(promoted)
        } catch (e: Exception) {
            deleteAside(aside)
            throw e
        }
        if (actualName != fileName) {
            undo(null, aside, fileName)
            throw PhoneStoreException("Le dossier du téléphone a nommé le fichier « $actualName » au lieu de « $fileName ».")
        }
        deleteAside(aside)
    }

    private fun deleteAside(aside: Uri?) {
        aside?.let { old -> runCatching { DocumentsContract.deleteDocument(resolver, old) } }
    }

    private fun cannotDelete(name: String) =
        PhoneStoreException("Le dossier du téléphone ne permet pas de supprimer « $name ». Supprime-le à la main.")

    /** Nom affiché du document. Une requête sans réponse, sans ligne ou sans nom ne dit rien du nom : elle lève [FileNotFoundException]. */
    private fun displayName(document: Uri): String =
        resolver.query(document, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { rows ->
            if (rows.moveToFirst()) rows.getString(0)?.takeIf { it.isNotEmpty() } else null
        } ?: throw FileNotFoundException(document.toString())

    private fun copy(source: File, target: Uri) {
        val output = resolver.openOutputStream(target, "wt") ?: throw FileNotFoundException(target.toString())
        output.use { out -> source.inputStream().use { it.copyTo(out) } }
    }

    /**
     * Exécute [block] et change toute exception en [PhoneStoreException], au message de l'utilisateur et avec l'exception d'origine pour cause.
     * Une [PhoneStoreException] déjà levée passe telle quelle, avec son message.
     */
    private fun <T> guarded(block: () -> T): T =
        try {
            block()
        } catch (e: PhoneStoreException) {
            throw e
        } catch (e: Exception) {
            throw PhoneStoreException(INACCESSIBLE, e)
        }

    companion object {
        private const val INACCESSIBLE = "Dossier du téléphone inaccessible. Choisis-le de nouveau dans Réglages."
        private const val NO_RENAME = "Le dossier du téléphone ne permet pas de renommer les fichiers. Choisis un autre dossier dans Réglages."
        private const val PART_SUFFIX = ".part"
        private const val OLD_SUFFIX = ".old"
        private const val PART_MIME = "application/octet-stream"
        private const val ID_COLUMN = 0
        private const val NAME_COLUMN = 1
        private const val SIZE_COLUMN = 2

        /** Dossier Téléchargements, où s'ouvre le sélecteur de dossier. */
        val initialUri: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")

        /** Retient le droit de lire et d'écrire dans le dossier choisi, au-delà du redémarrage du téléphone. */
        fun keep(context: Context, treeUri: Uri) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        }

        /** Vrai si le droit de lecture et d'écriture sur le dossier est retenu et si le système répond encore pour ce dossier. */
        fun hasAccess(context: Context, treeUri: Uri): Boolean {
            val resolver = context.contentResolver
            val kept = resolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission && it.isWritePermission }
            if (!kept) return false
            return try {
                val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                resolver.query(root, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
            } catch (e: Exception) {
                false
            }
        }

        /** Chemin affiché dans les réglages : `primary:Download/Dictée` devient `Download/Dictée`. */
        fun displayPath(treeUri: Uri): String {
            val id = DocumentsContract.getTreeDocumentId(treeUri)
            return id.substringAfter(':', id)
        }
    }
}
