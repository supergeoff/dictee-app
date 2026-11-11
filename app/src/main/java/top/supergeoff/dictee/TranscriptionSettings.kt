package top.supergeoff.dictee

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class TranscriptionSettings(
    val baseUrl: String = "/v1",
    val language: String = "fr",
    val model: String = "dictee-diarize",
    val credentialRef: String? = null,
)

/** Seul le défaut relatif ou une adresse HTTP(S) de serveur terminée par /v1 est admis. */
fun isValidTranscriptionBaseUrl(value: String): Boolean {
    if (value == "/v1") return true
    return try {
        val uri = URI(value)
        uri.scheme in setOf("http", "https") && uri.host != null &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath == "/v1" && uri.port in -1..65535 && uri.port != 0 &&
            !uri.rawAuthority.endsWith(":")
    } catch (_: Exception) { false }
}

class InvalidTranscriptionAddressException : Exception(
    "Adresse invalide. Utilise une adresse HTTP(S) terminée par /v1, sans identifiants, requête ni fragment."
)

interface CredentialStore {
    fun save(secret: String): String
    fun read(ref: String): String
    fun remove(ref: String)
}

/** Les erreurs ne contiennent ni clé, ni cause fournie par le fournisseur cryptographique. */
class CredentialException : Exception("Clé de transcription inaccessible. Vérifie les réglages.")

/** Le texte chiffré et son IV sont persistés dans des préférences privées, jamais dans state.json. */
class AndroidCredentialStore(context: Context, private val alias: String = "transcription-credentials-v1", preferencesName: String = "transcription-credentials") : CredentialStore {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    private fun key(create: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw CredentialException()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    override fun save(secret: String): String = synchronized(lock) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(true))
            val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
            val ref = UUID.randomUUID().toString()
            val encoded = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
            if (!preferences.edit().putString(ref, encoded).commit()) throw CredentialException()
            ref
        } catch (_: Exception) { throw CredentialException() }
    }

    override fun read(ref: String): String = synchronized(lock) {
        try {
            val bytes = Base64.decode(preferences.getString(ref, null) ?: throw CredentialException(), Base64.NO_WRAP)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        } catch (_: Exception) { throw CredentialException() }
    }

    override fun remove(ref: String) {
        synchronized(lock) {
            try { if (!preferences.edit().remove(ref).commit()) throw CredentialException() }
            catch (_: Exception) { throw CredentialException() }
        }
    }

    companion object { private val lock = Any() }
}

/** Partage le dépôt de l'application ; aucune ancienne référence n'est supprimée ici. */
class TranscriptionSettingsManager(private val repository: LocalStateRepository, private val credentials: CredentialStore) {
    private val mutex = Mutex()

    suspend fun save(settings: TranscriptionSettings, newSecret: String?) = mutex.withLock {
        if (!isValidTranscriptionBaseUrl(settings.baseUrl)) throw InvalidTranscriptionAddressException()
        try {
            repository.awaitInitialized()
            val newRef = newSecret?.takeIf { it.isNotEmpty() }?.let { credentials.save(it) }
            repository.updateDurably { state ->
                state.copy(transcriptionSettings = settings.copy(credentialRef = newRef ?: state.transcriptionSettings.credentialRef))
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw CredentialException() }
    }

    /** Appelé par un futur coordinateur avant tout effet réseau. */
    suspend fun readForTask(localId: String): String? {
        val task = repository.awaitInitialized().transcriptionTasks.firstOrNull { it.localId == localId } ?: return null
        return try {
            credentials.read(task.encryptedKeyRef)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            try {
                repository.updateDurably { state ->
                    state.copy(transcriptionTasks = state.transcriptionTasks.map { current ->
                        if (current.localId == localId && current.encryptedKeyRef == task.encryptedKeyRef &&
                            current.phase !in setOf(TranscriptionPhase.COMPLETED, TranscriptionPhase.FAILED, TranscriptionPhase.CANCELLED))
                            current.copy(suspended = true, suspensionReason = "Clé inaccessible. Vérifie les réglages de transcription.")
                        else current
                    })
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { throw CredentialException() }
            null
        }
    }
}
