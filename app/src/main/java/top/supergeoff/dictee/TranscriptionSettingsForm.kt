package top.supergeoff.dictee

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Brouillon en mémoire seulement, jamais écrit au journal ni dans l'état local. */
data class TranscriptionSettingsDraft(
    val baseUrl: String = "/v1",
    val language: String = "fr",
    val model: String = "dictee-diarize",
    val secret: String = "",
    val initialized: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
) {
    override fun toString(): String = "TranscriptionSettingsDraft(saving=$saving)"
}

/** Une seule sauvegarde acceptée à la fois, dès l'appel et avant la planification de la coroutine. */
class TranscriptionSettingsForm(
    private val scope: CoroutineScope,
    private val save: suspend (TranscriptionSettings, String) -> Unit,
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(TranscriptionSettingsDraft())
    val state = mutableState.asStateFlow()

    fun initialize(settings: TranscriptionSettings) = synchronized(lock) {
        if (!mutableState.value.initialized) {
            mutableState.value = TranscriptionSettingsDraft(settings.baseUrl, settings.language, settings.model, initialized = true)
        }
    }

    private fun edit(change: (TranscriptionSettingsDraft) -> TranscriptionSettingsDraft) = synchronized(lock) {
        val current = mutableState.value
        if (current.initialized && !current.saving) mutableState.value = change(current).copy(message = null)
    }
    fun setBaseUrl(value: String) = edit { it.copy(baseUrl = value) }
    fun setLanguage(value: String) = edit { it.copy(language = value) }
    fun setModel(value: String) = edit { it.copy(model = value) }
    fun setSecret(value: String) = edit { it.copy(secret = value) }

    fun submit(): Boolean = synchronized(lock) {
        val draft = mutableState.value
        if (!draft.initialized || draft.saving) return@synchronized false
        val settings = TranscriptionSettings(draft.baseUrl.trim(), draft.language.trim(), draft.model.trim())
        if (!isValidTranscriptionBaseUrl(settings.baseUrl)) {
            mutableState.value = draft.copy(message = InvalidTranscriptionAddressException().message)
            return@synchronized false
        }
        mutableState.value = draft.copy(saving = true, message = null)
        scope.launch {
            try {
                save(settings, draft.secret)
                synchronized(lock) {
                    mutableState.value = mutableState.value.copy(secret = "", saving = false, message = "Réglages de transcription enregistrés")
                }
            } catch (e: CancellationException) {
                synchronized(lock) { mutableState.value = mutableState.value.copy(saving = false, message = "Enregistrement interrompu. Réessaie.") }
                throw e
            } catch (e: Exception) {
                val message = if (e is InvalidTranscriptionAddressException) e.message else "Impossible d’enregistrer les réglages de transcription. Réessaie."
                synchronized(lock) { mutableState.value = mutableState.value.copy(saving = false, message = message) }
            }
        }
        true
    }
}
