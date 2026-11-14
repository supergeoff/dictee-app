package top.supergeoff.dictee

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    private val savePrompt: suspend (String) -> Unit,
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

    fun submit(): Boolean = operate(Operation.SERVICE)
    fun submitPrompt(): Boolean = operate(Operation.PROMPT)
    fun test(): Boolean = operate(Operation.TEST)

    private enum class Operation { SERVICE, PROMPT, TEST }

    private companion object { const val TEST_TIMEOUT_MS = 30_000L }

    private fun operate(operation: Operation): Boolean = synchronized(lock) {
        val testing = operation == Operation.TEST
        val draft = mutableState.value
        if (!draft.initialized || draft.saving || draft.testing) return@synchronized false
        val settings = SynthesisSettings(baseUrl = draft.baseUrl.trim(), model = draft.model.trim(), prompt = draft.prompt)
        if (operation != Operation.PROMPT && !isValidSynthesisBaseUrl(settings.baseUrl)) {
            mutableState.value = draft.copy(message = InvalidTranscriptionAddressException().message)
            return@synchronized false
        }
        if (operation == Operation.PROMPT && settings.prompt.isBlank()) {
            mutableState.value = draft.copy(message = "Le prompt ne peut pas être vide.")
            return@synchronized false
        }
        mutableState.value = draft.copy(saving = !testing, testing = testing, message = null)
        scope.launch {
            try {
                when (operation) {
                    Operation.TEST -> {
                        val completed = withTimeoutOrNull(TEST_TIMEOUT_MS) { test(settings, draft.secret); true } ?: false
                        if (!completed) throw SynthesisClientException(SynthesisFailureKind.TRANSIENT)
                    }
                    Operation.SERVICE -> save(settings, draft.secret)
                    Operation.PROMPT -> savePrompt(settings.prompt)
                }
                synchronized(lock) {
                    mutableState.value = mutableState.value.copy(
                        secret = if (operation == Operation.SERVICE) "" else draft.secret, saving = false, testing = false,
                        message = when (operation) {
                            Operation.TEST -> "Test de synthèse réussi."
                            Operation.SERVICE -> "Réglages de synthèse enregistrés"
                            Operation.PROMPT -> "Prompt enregistré pour les prochaines synthèses."
                        },
                    )
                }
            } catch (e: CancellationException) {
                synchronized(lock) { mutableState.value = mutableState.value.copy(saving = false, testing = false, message = "Opération interrompue. Réessaie.") }
                throw e
            } catch (e: Exception) {
                val message = if (testing) {
                    val reason = if (e is SynthesisClientException) e.message else "Vérifie les réglages."
                    "Test de synthèse échoué. $reason"
                } else if (operation == Operation.PROMPT) "Impossible d’enregistrer le prompt. Réessaie."
                else "Impossible d’enregistrer les réglages de synthèse. Réessaie."
                synchronized(lock) {
                    mutableState.value = mutableState.value.copy(saving = false, testing = false, message = message)
                }
            }
        }
        true
    }
}
