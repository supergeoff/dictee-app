package top.supergeoff.dictee

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/** Fournisseur synthétique : identifiants stables et fichiers privés au processus de test. */
class FakeDocumentsProvider : DocumentsProvider() {
    private data class Entry(var name: String, val file: File)
    private val entries = linkedMapOf<String, Entry>()
    private var nextId = 0
    var failRenameTo: String? = null
    var unreadablePromoted = false
    var appearOnChildQuery: Int? = null
    var childQueries = 0
    private var promotedId: String? = null

    override fun onCreate(): Boolean = true
    override fun isChildDocument(parentDocumentId: String, documentId: String) = parentDocumentId == ROOT && documentId in entries
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf("root_id"))
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        childQueries++
        if (childQueries == appearOnChildQuery) put("note.md", "appeared")
        val columns = projection ?: DEFAULT_COLUMNS
        return MatrixCursor(columns).apply { entries.forEach { (id, entry) -> addRow(row(columns, id, entry)) } }
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        if (unreadablePromoted && documentId == promotedId && projection?.contains(Document.COLUMN_DISPLAY_NAME) == true) throw FileNotFoundException("nom illisible")
        val columns = projection ?: DEFAULT_COLUMNS
        return MatrixCursor(columns).apply {
            if (documentId == ROOT) addRow(row(columns, ROOT, null))
            else addRow(row(columns, documentId, entries[documentId] ?: throw FileNotFoundException()))
        }
    }
    private fun row(columns: Array<out String>, id: String, entry: Entry?): Array<Any?> = columns.map {
        when (it) {
            Document.COLUMN_DOCUMENT_ID -> id
            Document.COLUMN_DISPLAY_NAME -> entry?.name ?: ROOT
            Document.COLUMN_SIZE -> entry?.file?.length() ?: 0L
            Document.COLUMN_FLAGS -> Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_DELETE
            Document.COLUMN_MIME_TYPE -> if (entry == null) Document.MIME_TYPE_DIR else "application/octet-stream"
            else -> null
        }
    }.toTypedArray()
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String = put(displayName, "")
    override fun renameDocument(documentId: String, displayName: String): String {
        if (failRenameTo == displayName) throw FileNotFoundException("échec de renommage")
        val entry = entries[documentId] ?: throw FileNotFoundException()
        val wasPart = entry.name.endsWith(".part")
        entry.name = displayName
        if (wasPart) promotedId = documentId
        return documentId
    }
    override fun deleteDocument(documentId: String) { entries.remove(documentId)?.file?.delete() }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = entries[documentId]?.file ?: throw FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }
    fun put(name: String, contents: String): String {
        val id = "doc${nextId++}"
        val file = File.createTempFile("fake-saf-", ".bin", context!!.cacheDir).apply { writeText(contents) }
        entries[id] = Entry(name, file)
        return id
    }
    fun contents(name: String): String? = entries.values.firstOrNull { it.name == name }?.file?.readText()
    fun reset() {
        entries.values.forEach { it.file.delete() }; entries.clear()
        failRenameTo = null; unreadablePromoted = false; appearOnChildQuery = null; childQueries = 0; promotedId = null
    }
    companion object {
        const val AUTHORITY = "top.supergeoff.dictee.test.documents"
        const val ROOT = "root"
        private val DEFAULT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_MIME_TYPE)
    }
}
