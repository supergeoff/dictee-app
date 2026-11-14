package top.supergeoff.dictee

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Brouillon uniquement en mémoire ; la clé ne figure jamais dans sa représentation textuelle. */
data class SynthesisSettingsDraft(
    val baseUrl: String = "/v1",
    val model: String = "",
    val prompt: String = DEFAULT_SYNTHESIS_PROMPT,
    val secret: String = "",
    val initialized: Boolean = false,
    val saving: Boolean = false,
    val testing: Boolean = false,
    val message: String? = null,
) {
    override fun toString(): String = "SynthesisSettingsDraft(saving=$saving, testing=$testing)"
}

/** Une seule opération, dès l'appel et avant la planification de la coroutine. */
class SynthesisSettingsForm(
    private val scope: CoroutineScope,
    private val save: suspend (SynthesisSettings, String) -> Unit,
    private val test: suspend (SynthesisSettings, String) -> Unit,
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(SynthesisSettingsDraft())
    val state = mutableState.asStateFlow()

    fun initialize(settings: SynthesisSettings) = synchronized(lock) {
        if (!mutableState.value.initialized) {
            mutableState.value = SynthesisSettingsDraft(settings.baseUrl, settings.model, settings.prompt, initialized = true)
        }
    }

    private fun edit(change: (SynthesisSettingsDraft) -> SynthesisSettingsDraft) = synchronized(lock) {
        val current = mutableState.value
        if (current.initialized && !current.saving && !current.testing) mutableState.value = change(current).copy(message = null)
    }
    fun setBaseUrl(value: String) = edit { it.copy(baseUrl = value) }
    fun setModel(value: String) = edit { it.copy(model = value) }
    fun setPrompt(value: String) = edit { it.copy(prompt = value) }
    fun setSecret(value: String) = edit { it.copy(secret = value) }

    fun submit(): Boolean = operate(testing = false)
    fun test(): Boolean = operate(testing = true)

    private fun operate(testing: Boolean): Boolean = synchronized(lock) {
        val draft = mutableState.value
        if (!draft.initialized || draft.saving || draft.testing) return@synchronized false
        val settings = SynthesisSettings(baseUrl = draft.baseUrl.trim(), model = draft.model.trim(), prompt = draft.prompt)
        if (!isValidSynthesisBaseUrl(settings.baseUrl)) {
            mutableState.value = draft.copy(message = InvalidTranscriptionAddressException().message)
            return@synchronized false
        }
        mutableState.value = draft.copy(saving = !testing, testing = testing, message = null)
        scope.launch {
            try {
                if (testing) test(settings, draft.secret) else save(settings, draft.secret)
                synchronized(lock) {
                    mutableState.value = mutableState.value.copy(
                        secret = if (testing) draft.secret else "", saving = false, testing = false,
                        message = if (testing) "Test de synthèse réussi." else "Réglages de synthèse enregistrés",
                    )
                }
            } catch (e: CancellationException) {
                synchronized(lock) { mutableState.value = mutableState.value.copy(saving = false, testing = false, message = "Opération interrompue. Réessaie.") }
                throw e
            } catch (_: Exception) {
                synchronized(lock) {
                    mutableState.value = mutableState.value.copy(saving = false, testing = false,
                        message = if (testing) "Test de synthèse échoué. Vérifie les réglages." else "Impossible d’enregistrer les réglages de synthèse. Réessaie.")
                }
            }
        }
        true
    }
}
