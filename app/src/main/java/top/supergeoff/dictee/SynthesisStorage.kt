package top.supergeoff.dictee

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Attente privée persistante ; les appels bloquants sont à effectuer sur Dispatchers.IO. */
class SynthesisStorage(private val privateDir: File) {
    fun stage(localId: String, bytes: ByteArray): String {
        val target = file(localId)
        if (!privateDir.isDirectory && !privateDir.mkdirs() && !privateDir.isDirectory) throw IOException("Stockage privé inaccessible")
        clearTemporaryFiles(localId)
        val temp = File.createTempFile("synthesis-$localId.", ".tmp", privateDir)
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            val hash = sha256(temp)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return hash
        } finally {
            temp.delete()
        }
    }

    /** Un stage publié atomiquement reste récupérable si state.json n'a pas été écrit. */
    fun stagedSha256(localId: String): String? {
        val target = file(localId)
        clearTemporaryFiles(localId)
        if (!target.exists()) return null
        if (!target.isFile) throw IOException("Synthèse privée inaccessible")
        return sha256(target)
    }

    fun staged(localId: String, expectedSha256: String): File {
        val target = file(localId)
        clearTemporaryFiles(localId)
        if (!target.isFile || sha256(target) != expectedSha256) throw IOException("Synthèse privée absente ou corrompue")
        return target
    }

    fun discard(localId: String) {
        val target = file(localId)
        clearTemporaryFiles(localId)
        if (target.exists() && !target.delete()) throw IOException("Suppression de la synthèse privée impossible")
    }

    /** Le point sépare l'ID sûr du suffixe aléatoire, même pour deux IDs ayant un préfixe commun. */
    private fun clearTemporaryFiles(localId: String) {
        if (!privateDir.exists()) return
        val entries = privateDir.listFiles() ?: throw IOException("Stockage privé inaccessible")
        val prefix = "synthesis-$localId."
        for (entry in entries) {
            if (entry.name.startsWith(prefix) && entry.name.endsWith(".tmp") && !entry.delete()) {
                throw IOException("Suppression de la synthèse privée temporaire impossible")
            }
        }
    }

    private fun file(localId: String): File {
        require(localId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Identifiant de tâche invalide" }
        return File(privateDir, "synthesis-$localId.md")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

enum class ProvenanceStatus { CURRENT, STALE, UNKNOWN }

fun provenanceStatus(provenance: SynthesisProvenance?, folderUri: String, vttSha256: String?, mdSha256: String?): ProvenanceStatus {
    if (provenance == null || provenance.folderUri != folderUri || vttSha256.isNullOrBlank() || mdSha256.isNullOrBlank() ||
        provenance.vttSha256.isBlank() || provenance.mdSha256.isBlank() || provenance.mdSha256 != mdSha256) return ProvenanceStatus.UNKNOWN
    return if (provenance.vttSha256 == vttSha256) ProvenanceStatus.CURRENT else ProvenanceStatus.STALE
}
