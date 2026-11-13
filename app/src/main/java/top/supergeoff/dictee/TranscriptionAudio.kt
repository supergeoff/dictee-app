package top.supergeoff.dictee

import java.security.MessageDigest

/** Empreinte du MP3, calculée en flux pour ne pas charger un long enregistrement en mémoire. */
object TranscriptionAudio {
    fun sha256(store: PhoneStore, fileName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        store.open(fileName).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Un MP3 absent ou illisible ne doit pas être envoyé. */
    fun matches(store: PhoneStore, task: TranscriptionTask): Boolean =
        try { sha256(store, task.mp3Name) == task.mp3Sha256 } catch (_: Exception) { false }
}
