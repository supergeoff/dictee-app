package top.supergeoff.dictee

import java.io.File

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
