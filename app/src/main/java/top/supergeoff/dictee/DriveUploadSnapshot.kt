package top.supergeoff.dictee

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Copie privée : le hachage et l'envoi lisent exactement les mêmes octets, même après une promotion SAF. */
class DriveUploadSnapshot private constructor(private val copy: File, val sha256: String, val size: Long) : Closeable {
    fun open(): InputStream = copy.inputStream()
    override fun close() { copy.delete() }

    companion object {
        fun capture(store: PhoneStore, fileName: String, cacheDir: File): DriveUploadSnapshot {
            val copy = File.createTempFile("drive-upload-", ".tmp", cacheDir)
            try {
                store.open(fileName).use { input -> copy.outputStream().use { input.copyTo(it) } }
                val digest = MessageDigest.getInstance("SHA-256")
                copy.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                return DriveUploadSnapshot(copy, digest.digest().joinToString("") { "%02x".format(it) }, copy.length())
            } catch (e: Throwable) {
                copy.delete()
                throw e
            }
        }
    }
}
