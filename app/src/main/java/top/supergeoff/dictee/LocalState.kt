package top.supergeoff.dictee

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/** Thème de l'interface, choisi dans les réglages. */
enum class ThemeChoice { LIGHT, DARK, SYSTEM }

/** Origine d'un dossier Drive connu : créé par l'app, ou choisi par l'utilisateur dans le sélecteur. */
enum class FolderOrigin { CREATED, PICKED }

/** Dossier Drive déjà utilisé, proposé à nouveau. */
data class KnownFolder(val id: String, val name: String, val origin: FolderOrigin)

/** Étape de traitement d'un enregistrement, dont l'erreur est gardée jusqu'à une réussite. */
enum class Step { RECOVER, SEND, TRANSCRIBE }

/** État local tenu dans `state.json`, version 4. */
data class LocalState(
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val boxList: List<ListItem.Entry> = emptyList(),
    val phoneTreeUri: String? = null,
    val driveEnabled: Boolean = false,
    /** Adresse du compte Google autorisé, nécessaire pour révoquer l'accès. */
    val driveAccount: String? = null,
    val knownFolders: List<KnownFolder> = emptyList(),
    val chosenFolderId: String? = null,
    val errors: Map<String, Map<Step, String>> = emptyMap(),   // nom → étape → dernière erreur
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val transcriptionTasks: List<TranscriptionTask> = emptyList(),
    val transcriptionSettings: TranscriptionSettings = TranscriptionSettings(),
    val transcriptionAddressNeedsReview: Boolean = false,
    val synthesisSettings: SynthesisSettings = SynthesisSettings(),
    val synthesisProvenance: List<SynthesisProvenance> = emptyList(),
) {
    /** Ajoute l'erreur de cette étape, ou remplace celle qu'elle avait déjà pour ce nom. */
    fun withError(name: String, step: Step, message: String): LocalState =
        copy(errors = errors + (name to (errors[name].orEmpty() + (step to message))))

    /** Retire l'erreur de cette étape ; le nom disparaît quand il ne lui reste aucune erreur. */
    fun withoutError(name: String, step: Step): LocalState {
        val steps = errors[name]?.minus(step) ?: return this
        return copy(errors = if (steps.isEmpty()) errors - name else errors + (name to steps))
    }

    /** Retire l'erreur de cette étape pour tous les noms. */
    fun withoutErrors(step: Step): LocalState = errors.keys.fold(this) { state, name -> state.withoutError(name, step) }
}

/** Applique [change] à l'état, de façon atomique. Vrai si l'état a changé, donc s'il faut l'écrire sur le disque. */
internal fun MutableStateFlow<LocalState>.updateChanged(change: (LocalState) -> LocalState): Boolean {
    var changed = false
    update { old -> change(old).also { changed = it != old } }   // si le CAS rejoue le calcul, le dernier tour décide
    return changed
}

/** L'écriture a échoué avant publication certaine de l'état. */
internal class LocalStateWriteNotPublishedException(cause: Exception? = null) :
    IOException("Impossible de remplacer state.json", cause)

/** Lit et écrit `state.json`. */
class LocalStateStore(private val file: File, private val rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) }) {

    /** Lit l'état sans modifier le fichier en cas d'erreur de lecture ou de contenu. */
    @Synchronized
    fun load(): LocalState {
        val content = try {
            file.readText()
        } catch (e: FileNotFoundException) {
            // exists() retourne aussi false en cas d'accès interdit. Seul ENOENT confirme l'absence.
            try {
                Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
            } catch (_: NoSuchFileException) {
                return LocalState()
            }
            throw e
        }
        val json = JSONObject(content)
        return if (json.has("version")) read(json) else migrate(json)
    }

    /** Écrit l'état dans `state.json.tmp`, puis le renomme sur `state.json`. */
    fun save(state: LocalState) = write(state, markPreparationFailure = false)

    internal fun saveForRepository(state: LocalState) = write(state, markPreparationFailure = true)

    @Synchronized
    private fun write(state: LocalState, markPreparationFailure: Boolean) {
        val json = try { prepareJson(state) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { if (markPreparationFailure) throw LocalStateWriteNotPublishedException(e) else throw e }
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(tmp).use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
          catch (e: Exception) { throw LocalStateWriteNotPublishedException(e) }
        // Une exception pendant le renommage ne garantit pas que celui-ci n'a pas eu lieu.
        if (!rename(tmp, file)) throw LocalStateWriteNotPublishedException()
    }

    private fun prepareJson(state: LocalState): String {
        if (!isValidTranscriptionBaseUrl(state.transcriptionSettings.baseUrl) ||
            !isValidSynthesisBaseUrl(state.synthesisSettings.baseUrl) ||
            state.transcriptionTasks.any { !isSafeStoredTaskAddress(it) || !isSafeStoredSynthesisAddress(it.synthesis) })
            throw InvalidTranscriptionAddressException()
        require(state.transcriptionTasks.all(::isSafeStoredTaskContext)) { "Contexte de transcription incomplet" }
        require(state.transcriptionTasks.all { it.synthesis?.let(::hasValidSynthesisContext) != false })
        val json = JSONObject()
            .put("version", VERSION)
            .put("deviceAddress", state.deviceAddress ?: JSONObject.NULL)
            .put("deviceName", state.deviceName ?: JSONObject.NULL)
            .put("boxList", JSONArray().apply {
                state.boxList.forEach { put(JSONObject().put("name", it.name).put("size", it.size).put("flag", it.flag)) }
            })
            .put("phoneTreeUri", state.phoneTreeUri ?: JSONObject.NULL)
            .put("driveEnabled", state.driveEnabled)
            .put("driveAccount", state.driveAccount ?: JSONObject.NULL)
            .put("knownFolders", JSONArray().apply {
                state.knownFolders.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("origin", it.origin.name)) }
            })
            .put("chosenFolderId", state.chosenFolderId ?: JSONObject.NULL)
            .put("errors", JSONObject().apply {
                state.errors.forEach { (name, steps) ->
                    put(name, JSONObject().apply { steps.forEach { (step, message) -> put(step.name, message) } })
                }
            })
            .put("theme", state.theme.name)
            .put("transcriptionAddressNeedsReview", state.transcriptionAddressNeedsReview)
            .put("transcriptionSettings", JSONObject()
                .put("baseUrl", state.transcriptionSettings.baseUrl)
                .put("language", state.transcriptionSettings.language)
                .put("model", state.transcriptionSettings.model)
                .put("credentialRef", state.transcriptionSettings.credentialRef ?: JSONObject.NULL))
            .put("synthesisSettings", JSONObject()
                .put("baseUrl", state.synthesisSettings.baseUrl)
                .put("model", state.synthesisSettings.model)
                .put("credentialRef", state.synthesisSettings.credentialRef ?: JSONObject.NULL)
                .put("prompt", state.synthesisSettings.prompt))
            .put("synthesisProvenance", JSONArray().apply {
                state.synthesisProvenance.forEach { provenance ->
                    put(JSONObject().put("folderUri", provenance.folderUri).put("mdName", provenance.mdName)
                        .put("model", provenance.model).put("vttSha256", provenance.vttSha256).put("mdSha256", provenance.mdSha256))
                }
            })
            .put("transcriptionTasks", JSONArray().apply {
                state.transcriptionTasks.forEach { task ->
                    put(JSONObject()
                        .put("localId", task.localId)
                        .put("recordingName", task.recordingName)
                        .put("mp3Name", task.mp3Name)
                        .put("vttName", task.vttName)
                        .put("folderUri", task.folderUri)
                        .put("mp3Sha256", task.mp3Sha256)
                        .put("serverAddress", task.serverAddress)
                        .put("encryptedKeyRef", task.encryptedKeyRef)
                        .put("language", task.language)
                        .put("model", task.model)
                        .put("phase", task.phase.name)
                        .put("serverId", task.serverId ?: JSONObject.NULL)
                        .put("progress", task.progress ?: JSONObject.NULL)
                        .put("error", task.error ?: JSONObject.NULL)
                        .put("suspended", task.suspended)
                        .put("suspensionReason", task.suspensionReason ?: JSONObject.NULL)
                        .put("replaceVtt", task.replaceVtt)
                        .put("submitAttempts", task.submitAttempts)
                        .put("nextAttemptAtMillis", task.nextAttemptAtMillis ?: JSONObject.NULL)
                        .put("synthesis", task.synthesis?.let { writeSynthesis(it) } ?: JSONObject.NULL))
                }
            })
        return json.toString(2)
    }

    private fun read(json: JSONObject): LocalState {
        val settings = json.objectOrNull("transcriptionSettings")?.let { readSettings(it) } ?: TranscriptionSettings()
        val invalidAddress = !isValidTranscriptionBaseUrl(settings.baseUrl)
        val version = json.getInt("version")
        val synthesisSettings = if (version >= 4) json.objectOrNull("synthesisSettings")?.let { readSynthesisSettings(it) }
            ?: SynthesisSettings() else SynthesisSettings()
        return LocalState(
            deviceAddress = json.stringOrNull("deviceAddress"),
            deviceName = json.stringOrNull("deviceName"),
            boxList = json.arrayOrNull("boxList")?.let { readBoxList(it) }.orEmpty(),
            phoneTreeUri = json.stringOrNull("phoneTreeUri"),
            driveEnabled = json.booleanOr("driveEnabled", false),
            driveAccount = json.stringOrNull("driveAccount"),
            knownFolders = json.arrayOrNull("knownFolders")?.let { readKnownFolders(it) }.orEmpty(),
            chosenFolderId = json.stringOrNull("chosenFolderId"),
            errors = json.objectOrNull("errors")?.let { readErrors(it) }.orEmpty(),
            theme = json.stringOrNull("theme").toEnumOr(ThemeChoice.SYSTEM),
            transcriptionTasks = json.arrayOrNull("transcriptionTasks")?.let { readTasks(it, version) }.orEmpty(),
            transcriptionSettings = if (invalidAddress) settings.copy(baseUrl = "/v1") else settings,
            transcriptionAddressNeedsReview = invalidAddress || json.booleanOr("transcriptionAddressNeedsReview", false),
            synthesisSettings = if (isValidSynthesisBaseUrl(synthesisSettings.baseUrl)) synthesisSettings else synthesisSettings.copy(baseUrl = "/v1"),
            synthesisProvenance = if (version >= 4) json.arrayOrNull("synthesisProvenance")?.let { readSynthesisProvenance(it) }.orEmpty() else emptyList(),
        )
    }

    /** Reprise de la 0.1.5 : boîtier et nom gardés, dossier Drive devenu dossier connu, enregistrements abandonnés. */
    private fun migrate(json: JSONObject): LocalState {
        val state = LocalState(
            deviceAddress = json.stringOrNull("deviceAddress"),
            deviceName = json.stringOrNull("deviceName"),
        )
        val folderId = json.stringOrNull("driveFolderId") ?: return state
        val origin = if (json.booleanOr("driveFolderPicked", false)) FolderOrigin.PICKED else FolderOrigin.CREATED
        return state.copy(
            driveEnabled = true,
            knownFolders = listOf(KnownFolder(folderId, json.stringOrNull("driveFolderName") ?: "dictee", origin)),
            chosenFolderId = folderId,
        )
    }

    private fun readBoxList(array: JSONArray): List<ListItem.Entry> = List(array.length()) { i ->
        val o = array.getJSONObject(i)
        ListItem.Entry(o.getString("name"), o.getLong("size"), o.intOr("flag", -1))
    }

    private fun readKnownFolders(array: JSONArray): List<KnownFolder> = List(array.length()) { i ->
        val o = array.getJSONObject(i)
        KnownFolder(o.getString("id"), o.getString("name"), o.stringOrNull("origin").toEnumOr(FolderOrigin.CREATED))
    }

    private fun readSettings(o: JSONObject): TranscriptionSettings {
        val defaults = TranscriptionSettings()
        return TranscriptionSettings(
            baseUrl = o.stringOrNull("baseUrl") ?: defaults.baseUrl,
            language = o.stringOrNull("language") ?: defaults.language,
            model = o.stringOrNull("model") ?: defaults.model,
            credentialRef = o.stringOrNull("credentialRef"),
        )
    }

    private fun readTasks(array: JSONArray, version: Int): List<TranscriptionTask> = List(array.length()) { i ->
        val o = array.getJSONObject(i)
        val phaseName = o.getString("phase")
        val phase = TranscriptionPhase.entries.firstOrNull { it.name == phaseName }
            ?: throw JSONException("Phase de transcription inconnue : $phaseName")
        val task = TranscriptionTask(
            localId = o.getString("localId"), recordingName = o.getString("recordingName"),
            mp3Name = o.getString("mp3Name"), vttName = o.getString("vttName"),
            folderUri = o.getString("folderUri"), mp3Sha256 = o.getString("mp3Sha256"),
            serverAddress = o.getString("serverAddress"), encryptedKeyRef = o.getString("encryptedKeyRef"),
            language = o.getString("language"), model = o.getString("model"), phase = phase,
            serverId = o.stringOrNull("serverId"), progress = if (o.isNull("progress")) null else o.getDouble("progress"),
            error = o.stringOrNull("error"), suspended = o.booleanOr("suspended", false),
            suspensionReason = o.stringOrNull("suspensionReason"),
            synthesis = if (version >= 4) o.objectOrNull("synthesis")?.let { readSynthesis(it) } else null,
            replaceVtt = if (version >= 4) o.booleanOr("replaceVtt", true) else true,
            submitAttempts = o.intOr("submitAttempts", 0),
            nextAttemptAtMillis = if (o.isNull("nextAttemptAtMillis")) null else o.getLong("nextAttemptAtMillis"),
        )
        val addressSafeTask = when {
            // Corrige les suspensions terminales enregistrées par la migration précédente.
            task.phase.isTerminal() && task.serverAddress.isEmpty() &&
                task.suspended && task.suspensionReason == INVALID_TASK_ADDRESS_REASON ->
                task.copy(suspended = false, suspensionReason = null)
            isSafeStoredTaskAddress(task) -> task
            task.phase.isTerminal() -> task.copy(serverAddress = "")
            else -> task.copy(serverAddress = "", suspended = true, suspensionReason = INVALID_TASK_ADDRESS_REASON)
        }
        if (isSafeStoredTaskContext(addressSafeTask)) addressSafeTask
        else addressSafeTask.copy(suspended = true, suspensionReason = INVALID_TASK_CONTEXT_REASON)
    }

    private fun writeSynthesis(work: SynthesisWork): JSONObject = JSONObject()
        .put("phase", work.phase.name).put("mdName", work.mdName).put("replaceMd", work.replaceMd)
        .put("context", work.context?.let { context -> JSONObject()
            .put("baseUrl", context.baseUrl).put("credentialRef", context.credentialRef).put("model", context.model)
            .put("promptTemplate", context.promptTemplate)
            .put("metadata", JSONObject().put("type", context.metadata.type).put("date", context.metadata.date).put("duree", context.metadata.duree))
            .put("systemPrompt", context.systemPrompt ?: JSONObject.NULL).put("vttSha256", context.vttSha256 ?: JSONObject.NULL)
        } ?: JSONObject.NULL)
        .put("attempts", work.attempts).put("nextAttemptAtMillis", work.nextAttemptAtMillis ?: JSONObject.NULL)
        .put("stagedSha256", work.stagedSha256 ?: JSONObject.NULL).put("suspensionReason", work.suspensionReason ?: JSONObject.NULL)
        .put("promotionStarted", work.promotionStarted).put("previousMdSha256", work.previousMdSha256 ?: JSONObject.NULL)

    private fun readSynthesisSettings(o: JSONObject): SynthesisSettings {
        val defaults = SynthesisSettings()
        return SynthesisSettings(o.stringOrNull("baseUrl") ?: defaults.baseUrl, o.stringOrNull("model") ?: defaults.model,
            o.stringOrNull("credentialRef"), o.stringOrNull("prompt") ?: defaults.prompt)
    }

    private fun readSynthesisProvenance(array: JSONArray): List<SynthesisProvenance> = List(array.length()) { i ->
        val o = array.getJSONObject(i)
        SynthesisProvenance(o.getString("folderUri"), o.getString("mdName"), o.getString("model"),
            o.getString("vttSha256"), o.getString("mdSha256"))
    }

    private fun readSynthesis(o: JSONObject): SynthesisWork {
        val phaseName = o.getString("phase")
        val phase = SynthesisPhase.entries.firstOrNull { it.name == phaseName }
            ?: throw JSONException("Phase de synthèse inconnue : $phaseName")
        val context = o.objectOrNull("context")?.let { c ->
            val metadata = c.getJSONObject("metadata")
            SynthesisContext(c.getString("baseUrl"), c.getString("credentialRef"), c.getString("model"),
                c.getString("promptTemplate"), PromptMetadata(metadata.getString("type"), metadata.getString("date"), metadata.getString("duree")),
                c.stringOrNull("systemPrompt"), c.stringOrNull("vttSha256"))
        }
        val work = SynthesisWork(phase, o.getString("mdName"), o.getBoolean("replaceMd"), context,
            o.getInt("attempts"), if (o.isNull("nextAttemptAtMillis")) null else o.getLong("nextAttemptAtMillis"),
            o.stringOrNull("stagedSha256"), o.stringOrNull("suspensionReason"),
            o.booleanOr("promotionStarted", false), o.stringOrNull("previousMdSha256"))
        if (!hasValidSynthesisContext(work)) throw JSONException("Contexte de synthèse absent pour la phase $phaseName")
        if (isSafeStoredSynthesisAddress(work)) return work
        if (phase == SynthesisPhase.COMPLETED || phase == SynthesisPhase.CANCELLED)
            return work.copy(context = context?.copy(baseUrl = ""))
        return work.copy(phase = SynthesisPhase.SUSPENDED, context = context?.copy(baseUrl = ""),
            suspensionReason = INVALID_SYNTHESIS_ADDRESS_REASON)
    }

    /** Les noms et les étapes inconnus sont ignorés, de même que les erreurs sans message. */
    private fun readErrors(obj: JSONObject): Map<String, Map<Step, String>> {
        val errors = mutableMapOf<String, Map<Step, String>>()
        for (name in obj.keys()) {
            val steps = obj.objectOrNull(name) ?: continue
            val byStep = mutableMapOf<Step, String>()
            for (stepName in steps.keys()) {
                val step = runCatching { Step.valueOf(stepName) }.getOrNull() ?: continue
                byStep[step] = steps.stringOrNull(stepName) ?: continue
            }
            if (byStep.isNotEmpty()) errors[name] = byStep
        }
        return errors
    }

    private companion object {
        const val VERSION = 4
    }
}

/** État de la lecture initiale, qui peut être relancée après un échec. */
sealed interface LocalStateInitialization {
    data object Loading : LocalStateInitialization
    data object Ready : LocalStateInitialization
    data class Failed(val error: Exception) : LocalStateInitialization
}

/** Une file unique applique les transformations et écrit hors du thread appelant. */
class LocalStateRepository(
    private val store: LocalStateStore,
    private val load: () -> LocalState = { store.load() },
    private val onSaveError: (Exception) -> Unit = {},
) {
    private data class Change(
        val transform: (LocalState) -> LocalState,
        val forceSave: Boolean,
        val initialization: CompletableDeferred<LocalState>,
        val result: CompletableDeferred<Result<LocalState>>,
    )

    private val mutableState = MutableStateFlow<LocalState?>(null)
    /** Null jusqu'à la fin du chargement, puis uniquement des états enregistrés. */
    val state: StateFlow<LocalState?> = mutableState.asStateFlow()
    private val mutableInitialization = MutableStateFlow<LocalStateInitialization>(LocalStateInitialization.Loading)
    val initialization: StateFlow<LocalStateInitialization> = mutableInitialization.asStateFlow()
    private val initializationLock = Any()
    private var initialized = CompletableDeferred<LocalState>()
    private val queue = Channel<Change>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        launchLoad(initialized)
        scope.launch {
            for (change in queue) {
                val result = try {
                    change.initialization.await()
                    val old = checkNotNull(mutableState.value)
                    val next = change.transform(old)
                    if (change.forceSave || next != old) {
                        store.saveForRepository(next)
                        mutableState.value = next
                    }
                    Result.success(next)
                } catch (e: Exception) {
                    if (!change.forceSave) {
                        try { onSaveError(e) } catch (_: Exception) { }
                    }
                    Result.failure(e)
                }
                change.result.complete(result)
            }
        }
    }

    private fun launchLoad(attempt: CompletableDeferred<LocalState>) {
        scope.launch {
            try {
                val loaded = load()
                synchronized(initializationLock) {
                    mutableState.value = loaded
                    mutableInitialization.value = LocalStateInitialization.Ready
                    attempt.complete(loaded)
                }
            } catch (e: Exception) {
                synchronized(initializationLock) {
                    mutableInitialization.value = LocalStateInitialization.Failed(e)
                    attempt.completeExceptionally(e)
                }
            }
        }
    }

    /** Relance uniquement une lecture échouée, sans réinitialiser ni écrire les données. */
    fun retryInitialization(): Boolean = synchronized(initializationLock) {
        if (mutableInitialization.value !is LocalStateInitialization.Failed) return@synchronized false
        val attempt = CompletableDeferred<LocalState>()
        initialized = attempt
        mutableInitialization.value = LocalStateInitialization.Loading
        launchLoad(attempt)
        true
    }

    /** Attend la tentative en cours, ou propage son erreur sans exécuter l'opération. */
    suspend fun awaitInitialized(): LocalState {
        val attempt = synchronized(initializationLock) { initialized }
        attempt.await()
        return checkNotNull(mutableState.value)
    }

    /** L'enregistrement est mis en file avant le retour, sans attendre le disque. */
    fun update(change: (LocalState) -> LocalState): Deferred<Result<LocalState>> = enqueue(change, false)

    /** Attend l'écriture et propage son erreur, y compris pour un état inchangé. */
    suspend fun updateDurably(change: (LocalState) -> LocalState): LocalState =
        enqueue(change, true).await().getOrThrow()

    private fun enqueue(change: (LocalState) -> LocalState, forceSave: Boolean): Deferred<Result<LocalState>> {
        val result = CompletableDeferred<Result<LocalState>>()
        synchronized(initializationLock) {
            check(queue.trySend(Change(change, forceSave, initialized, result)).isSuccess)
        }
        return result
    }
}

/** Valeur d'une clé texte, ou null si elle est absente ou nulle. */
private fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else getString(key)

private fun JSONObject.booleanOr(key: String, default: Boolean): Boolean =
    if (isNull(key)) default else getBoolean(key)

private fun JSONObject.intOr(key: String, default: Int): Int =
    if (isNull(key)) default else getInt(key)

private fun JSONObject.arrayOrNull(key: String): JSONArray? =
    if (isNull(key)) null else getJSONArray(key)

private fun JSONObject.objectOrNull(key: String): JSONObject? =
    if (isNull(key)) null else getJSONObject(key)

/** Énumération lue par son nom, ou [default] quand le nom est absent ou inconnu. */
private inline fun <reified T : Enum<T>> String?.toEnumOr(default: T): T =
    enumValues<T>().firstOrNull { it.name == this } ?: default

/** Sérialise les transferts et marque durablement un MP3 avant de pouvoir l'écrire.
 * Les futurs traitements lisant des MP3 doivent aussi utiliser ce verrou et l'état du dépôt. */
class RecoverySafety(private val repository: LocalStateRepository) {
    private val transferMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun <T> withTransferLock(block: suspend () -> T): T = locked(block)

    /** [onWaited] est appelé quand l'attente du verrou dépasse [waitThresholdMs], avant l'exécution de [block]. */
    suspend fun <T> withTransferLock(waitThresholdMs: Long, onWaited: () -> Unit, block: suspend () -> T): T =
        locked(block, waitThresholdMs, onWaited)

    private suspend fun <T> locked(block: suspend () -> T, waitThresholdMs: Long? = null, onWaited: (() -> Unit)? = null): T {
        repository.awaitInitialized()
        val started = System.currentTimeMillis()
        transferMutex.lock()
        if (waitThresholdMs != null && System.currentTimeMillis() - started > waitThresholdMs) onWaited?.invoke()
        try { return block() } finally { transferMutex.unlock() }
    }

    suspend fun begin(name: String) {
        try {
            repository.updateDurably { it.withError(name, Step.RECOVER, "Récupération interrompue ou en cours") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RecoveryGuardException(e)
        }
    }

    suspend fun finish(result: RecoveryResult) {
        repository.update { state ->
            when (result) {
                is RecoveryResult.Ok -> state.withoutError(result.name, Step.RECOVER)
                is RecoveryResult.Failed -> state.withError(result.name, Step.RECOVER, result.reason)
            }
        }.await()
    }
}

class RecoveryGuardException(cause: Exception) : IOException("Impossible de sécuriser la récupération : ${cause.message}", cause)

/** Le modèle et les autres composants reçoivent le même dépôt. */
class AppLocalState(val repository: LocalStateRepository) {
    fun setTheme(theme: ThemeChoice) = repository.update { it.copy(theme = theme) }
}
