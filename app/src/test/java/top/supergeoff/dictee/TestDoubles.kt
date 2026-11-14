package top.supergeoff.dictee

import java.io.*
import java.net.*

/** Journal en mémoire : une chaîne par ligne, de la forme « SOURCE message » ou « SOURCE erreur : message ». */
class ListLog : Log {
    val lines = mutableListOf<String>()

    override fun add(source: Source, message: String) {
        lines += "$source $message"
    }

    override fun error(source: Source, message: String) {
        lines += "$source erreur : $message"
    }
}

/** Dossier du téléphone en mémoire. [failWrites] simule un dossier perdu ou plein. */
class MemoryStore(var failWrites: Boolean = false) : PhoneStore {
    val files = linkedMapOf<String, ByteArray>()

    override fun list() = files.map { PhoneFile(it.key, it.value.size.toLong()) }

    override fun write(fileName: String, source: File) {
        if (failWrites) throw PhoneStoreException("Dossier du téléphone inaccessible. Choisis-le de nouveau dans Réglages.")
        files[fileName] = source.readBytes()
    }

    override fun open(fileName: String) = (files[fileName] ?: throw PhoneStoreException("$fileName absent")).inputStream()
}

internal class FakeCredentials : CredentialStore {
    val refs = mutableListOf<String>()
    override fun read(ref: String): String { refs += ref; return "synthetic-secret" }
    override fun save(secret: String) = "ref"
    override fun remove(ref: String) {}
}
internal class FakeConnection(url: URL, val status: Int = 200, val body: ByteArray = ByteArray(0), val type: String = "application/json") : HttpURLConnection(url) {
    val written = ByteArrayOutputStream()
    val log = mutableListOf<String>()
    @Volatile var closed = false
    var outputFactory: (() -> OutputStream)? = null
    override fun connect() {}
    override fun disconnect() { closed = true }
    override fun usingProxy() = false
    override fun getResponseCode() = status
    override fun getContentType() = type
    override fun getContentLengthLong() = body.size.toLong()
    override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    override fun getErrorStream(): InputStream = ByteArrayInputStream(body)
    override fun getOutputStream(): OutputStream = outputFactory?.invoke() ?: written
}
