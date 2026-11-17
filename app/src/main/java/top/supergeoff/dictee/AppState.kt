package top.supergeoff.dictee

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** État affiché par les écrans : tout ce qu'ils lisent, réuni par [AppModel]. */
data class UiState(
    val local: LocalState = LocalState(),
    val initialized: Boolean = true,
    val initializationError: String? = null,
    val recordings: List<Recording> = emptyList(),
    val link: LinkStatus = LinkStatus.NONE,
    val refreshing: Boolean = false,
    val recovering: Boolean = false,
    val sending: Boolean = false,
    val recovery: Progress? = null,
    val send: Progress? = null,
    val driveSendRequest: DriveSendRequest? = null,
    val deletionRequest: UnifiedDeletionPlan? = null,
    val deleting: Boolean = false,
    val notice: String? = null,
    val transcription: Progress? = null,
    val phoneFolder: String? = null,      // chemin affiché, null sans dossier
    val phoneFolderOk: Boolean = false,
    val phoneRevision: Long = 0,
    val documentHashes: Map<String, String> = emptyMap(),
    val drive: DriveListing? = null,      // null si Drive est désactivé ou illisible
    val busy: Busy = Busy(),
    val message: String? = null,
) {
    /** Enregistrements du boîtier à récupérer. */
    val toRecover: List<Recording>
        get() = Recordings.toRecover(recordings, drive)

    /** Enregistrements à envoyer : aucun si Drive est désactivé. */
    val toSend: List<Recording>
        get() = if (local.driveEnabled) Recordings.toSendCurrent(recordings, drive, local, documentHashes) else emptyList()
}

internal fun recordingListPresentation(recording: Recording, ui: UiState, provenance: ProvenanceStatus = ProvenanceStatus.UNKNOWN): PipelinePresentation {
    val settings = ui.local.synthesisSettings
    val configured = settings.baseUrl != "/v1" && isValidSynthesisBaseUrl(settings.baseUrl) &&
        settings.model.isNotBlank() && settings.prompt.isNotBlank() && !settings.credentialRef.isNullOrBlank()
    return RecordingPipeline.calculate(PipelineInput(recording, ui.local, ui.busy, ui.drive,
        ui.link == LinkStatus.CONNECTED && !ui.recovering, configured, provenance, ui.documentHashes))
}

/** État à afficher avant que les écrans puissent lire l'état local. */
internal fun initialUiState(local: LocalState?, initialization: LocalStateInitialization): UiState? =
    when (initialization) {
        LocalStateInitialization.Loading -> UiState(initialized = false)
        is LocalStateInitialization.Failed -> UiState(
            initialized = false,
            initializationError = initialization.error.message ?: initialization.error.javaClass.simpleName,
        )
        LocalStateInitialization.Ready -> if (local == null) UiState(initialized = false) else null
    }

/** Demande faite à l'activité, qui seule parle au système ; la réponse revient par la méthode `on...` de [AppModel]. */
sealed class UiRequest {
    data object BluetoothPermission : UiRequest()
    data object NotificationPermission : UiRequest()
    data object PickPhoneFolder : UiRequest()
    data class GoogleConsent(val intent: PendingIntent) : UiRequest()
    data class GooglePicker(val intent: PendingIntent) : UiRequest()
}

/** Dossier du téléphone tel que lu : ses fichiers, et s'il répond. */
internal data class PhoneSnapshot(val files: List<PhoneFile> = emptyList(), val ok: Boolean = false, val revision: Long = 0, val documentHashes: Map<String, String> = emptyMap(), val folderUri: String? = null) {
    fun hashesFor(folder: String?): Map<String, String> = if (ok && folder != null && folder == folderUri) documentHashes else emptyMap()
    fun afterPromotion(): PhoneSnapshot = copy(revision = revision + 1)
}

internal fun readDocumentHashes(files: List<PhoneFile>, readHash: (String) -> String, onError: (Exception) -> Unit): Map<String, String> =
    files.mapNotNull { file ->
        val kind = RecordName.parse(file.fileName)?.kind
        if (kind != FileKind.VTT && kind != FileKind.MD) null else {
            val hash = try { readHash(file.fileName) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { onError(e); null }
            hash?.let { file.fileName to it }
        }
    }.toMap()

/** Ce qui n'est pas dans `state.json` : travaux en cours avec leur avancement, et message à afficher. */
private data class Live(
    val refreshing: Boolean = false,
    val recovering: Boolean = false,
    val sending: Boolean = false,
    val recovery: Progress? = null,
    val recoveryName: String? = null,
    val send: Progress? = null,
    val sendName: String? = null,
    val sendNames: Set<String> = emptySet(),
    val driveSendRequest: DriveSendRequest? = null,
    val deletionRequest: UnifiedDeletionPlan? = null,
    val deleting: Boolean = false,
    val notice: String? = null,
    val recoveryNames: Set<String> = emptySet(),
    val message: String? = null,
)

internal fun deletionProtectedNames(local: LocalState, recoveryNames: Set<String>, sendNames: Set<String>): Set<String> =
    local.transcriptionTasks.filterNot { it.phase.isTerminal() }.map { it.recordingName }.toSet() +
        local.automaticProcessingRequests.map { it.recordingName } + recoveryNames + sendNames

/** Noms de la suppression qui sont déjà occupés (traitement, récupération, envoi) : un seul suffit à bloquer le lot. */
internal fun unifiedDeletionBusyNames(names: Set<String>, protectedNames: Set<String>, admittedBusy: Set<String>): Set<String> =
    names intersect (protectedNames + admittedBusy)

internal fun launchProcessingRequest(
    scope: CoroutineScope, guard: RecoveryDeletionGuard, names: Set<String>?, block: suspend () -> Unit,
): kotlinx.coroutines.Job? {
    val admission = guard.admitProcessing(names) ?: return null
    return try {
        scope.launch { block() }.also { it.invokeOnCompletion { admission.close() } }
    } catch (e: Throwable) {
        admission.close()
        throw e
    }
}

private val BLUETOOTH_PERMISSIONS = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
private const val PHONE_FOLDER_LOST = "Dossier du téléphone inaccessible. Choisis-le de nouveau dans Réglages."
private const val OUT_OF_RANGE = "Boîtier hors de portée."
private const val BOX_OUT_OF_RANGE = "hors de portée"
private const val NO_FOLDER = "Aucun dossier choisi"
private const val WAIT_FOR_TRANSFER = "Attends la fin du transfert pour changer de dossier."

/** Résultat de [block], ou l'exception qu'il a levée. L'annulation remonte. */
private inline fun <T> attempt(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

/**
 * Message d'une erreur, en déballant les enveloppes qui cachent la cause (initialiseur statique,
 * réflexion, classe rendue fautive par un premier échec) : le journal doit nommer l'erreur réelle.
 */
private fun reasonOf(e: Throwable): String {
    var cause: Throwable = e
    var depth = 0
    while (cause.cause != null && depth < 6 &&
        (cause is ExceptionInInitializerError || cause is java.lang.reflect.InvocationTargetException ||
            cause is NoClassDefFoundError || cause is ClassNotFoundException)) {
        cause = cause.cause!!
        depth++
    }
    return cause.message ?: cause.javaClass.simpleName
}

/**
 * Modèle de l'app : l'état local, la liste du téléphone, celle de Drive, la liaison et les travaux, réunis dans [ui].
 * Les écrans appellent ses actions. Ce qui demande le système (permission, dossier, accord Google) sort par [requests] :
 * l'action en attente est gardée, puis reprise par la méthode `on...` qui reçoit la réponse, ou abandonnée avec un message.
 */
class AppModel(
    private val app: Application,
    repository: LocalStateRepository? = null,
    private val transcriptionCoordinatorFactory: ((LocalStateRepository) -> TranscriptionCoordinator)? = null,
) {
    val journal = Journal(File(app.filesDir, "journal")) { LocalDateTime.now() }
    private val liveFlow = MutableStateFlow(Live())
    private val driveSendRequests = DriveSendRequests()
    private val refreshCoordinator = RefreshCoordinator { refreshing ->
        liveFlow.update { it.copy(refreshing = refreshing) }
    }
    val localRepository = repository ?: LocalStateRepository(LocalStateStore(File(app.filesDir, "state.json"))) { e ->
        val message = "Impossible d'enregistrer l'état local : ${reasonOf(e)}"
        say(message)
        journal.error(Source.FS, message)
    }
    private val localFlow: LocalState
        get() = checkNotNull(localRepository.state.value) { "L'état local n'est pas encore chargé" }
    private val appLocalState = AppLocalState(localRepository)
    val recoverySafety = RecoverySafety(localRepository)
    private val recoveryDeletionGuard = RecoveryDeletionGuard()
    private val transcriptionCredentials = AndroidCredentialStore(app)
    val transcriptionSettings = TranscriptionSettingsManager(localRepository, transcriptionCredentials)
    private val synthesisCredentials = AndroidCredentialStore(app, "synthesis-credentials-v1", "synthesis-credentials")
    val synthesisSettings = SynthesisSettingsManager(localRepository, synthesisCredentials)
    private val synthesisClient = UrlSynthesisClient(synthesisCredentials, log = journal)
    internal val transcriptionCoordinator: TranscriptionCoordinator by lazy {
        transcriptionCoordinatorFactory?.let { return@lazy it(localRepository) }
        val scheduler = TranscriptionSchedule(androidx.work.WorkManager.getInstance(app))
        val client = UrlTranscriptionClient(transcriptionCredentials, { task ->
            PhoneFolder(app, task.folderUri.toUri()).open(task.mp3Name)
        }, log = journal)
        TranscriptionCoordinator(localRepository, client, transcriptionSettings, recoverySafety,
            verifyAudio = { task -> withContext(Dispatchers.IO) {
                TranscriptionAudio.matches(PhoneFolder(app, task.folderUri.toUri()), task)
            } },
            prepareRequested = { names, request, active -> withContext(Dispatchers.IO) {
                val state = localRepository.awaitInitialized()
                val folder = state.phoneTreeUri ?: throw PhoneStoreException(PHONE_FOLDER_LOST)
                TranscriptionPreparation.prepare(PhoneFolder(app, folder.toUri()), folder,
                    state.transcriptionSettings, state.errors, names, state.synthesisSettings, request, active) { java.util.UUID.randomUUID().toString() }
            } },
            prepareAutomatic = { name, folder -> withContext(Dispatchers.IO) {
                val state = localRepository.awaitInitialized()
                TranscriptionPreparation.prepareAutomatic(PhoneFolder(app, folder.toUri()), folder,
                    state.transcriptionSettings, state.errors, name, state.synthesisSettings) { java.util.UUID.randomUUID().toString() }
            } },
            synthesisClient = synthesisClient,
            synthesisStorage = SynthesisStorage(File(app.filesDir, "synthesis")),
            phoneStore = { folder -> PhoneFolder(app, folder.toUri()) },
            synthesisCredentialAccessible = { ref -> runCatching { synthesisCredentials.read(ref).isNotBlank() }.getOrDefault(false) },
            schedule = scheduler::enqueue,
            log = journal,
            saveVtt = { task, bytes, promote -> withContext(Dispatchers.IO) {
                val spool = java.io.File(app.cacheDir, "vtt-" + java.util.UUID.randomUUID())
                try {
                    spool.writeBytes(bytes)
                    PhoneFolder(app, task.folderUri.toUri()).writeGuarded(task.vttName, spool, task.replaceVtt, promote)
                } catch (e: java.io.IOException) {
                    throw PhoneStoreException("Écriture impossible dans le dossier du téléphone.", e)
                } finally { spool.delete() }
            } },
            onFilesPromoted = { folder -> readPhoneAfterPromotion(folder) },
            onProcessingResult = ProcessingNotifications(app)::show,
            launch = { transcriptionStarter.requestAutomatic() })
    }

    private val automaticProcessingCoordinator by lazy {
        AutomaticProcessingCoordinator(localRepository, transcriptionCoordinator, journal)
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> journal.error(Source.APP, reasonOf(e)) },
    )

    init {
        scope.launch(Dispatchers.IO) {
            journal.purge()
            journal.add(Source.APP, "dictee ${BuildConfig.VERSION_NAME} sur Android ${Build.VERSION.RELEASE}")
            val leftovers = File(app.filesDir, "audio").listFiles { file -> file.isFile && file.name.endsWith(".mp3") }?.size ?: 0
            if (leftovers > 0) journal.add(Source.APP, "$leftovers MP3 de la version 0.1.5 restent dans le stockage interne")
        }
    }

    private val auth = GoogleAuth(app)
    private val box = BoxConnection(app, journal)
    private val saver = Dispatchers.IO.limitedParallelism(1)
    private val phoneFlow = MutableStateFlow(PhoneSnapshot())
    private val driveFlow = MutableStateFlow<DriveListing?>(null)
    private val requestFlow = MutableSharedFlow<UiRequest>(extraBufferCapacity = 8)
    private val phoneMutex = Mutex()
    private val deletionRequestLock = Any()
    private val driveMutex = Mutex()
    private val loggedFlags = ConcurrentHashMap.newKeySet<String>()
    private val notificationAsked = AtomicBoolean(false)
    private val accountFailureNoted = AtomicBoolean(false)
    internal val jobs = ActiveWork()

    private val foregroundBoxGate = ForegroundBoxGate()
    private val transcriptionLifecycle by lazy { InteractiveTranscriptionLifecycle(scope, ::followTranscriptions) }
    private val transcriptionStarter by lazy {
        InteractiveTranscriptionStarter(scope, jobs, ::startWork, transcriptionLifecycle,
            canResume = { foregroundBoxGate.isForeground },
            canLaunchAutomatically = { foregroundBoxGate.isForeground || liveFlow.value.recovering })
    }

    init {
        scope.launch { reconcileProcessing() }
    }
    @Volatile private var searching = false   // la feuille de recherche du boîtier est ouverte : le modèle ne rouvre pas la liaison
    @Volatile private var afterBluetooth: (() -> Unit)? = null
    @Volatile private var afterPhoneFolder: (() -> Unit)? = null
    @Volatile private var afterGoogle: (() -> Unit)? = null

    val requests: SharedFlow<UiRequest> = requestFlow.asSharedFlow()

    /**
     * Travaux interactifs lancés et pas finis, lus par [WorkService]. Ce compte observable monte avant le démarrage
     * du service et redescend même lorsqu'aucune modification de [ui] ne signale la fin.
     */
    val activeJobs: Int
        get() = jobs.value

    private data class LocalSnapshot(val state: LocalState?, val initialization: LocalStateInitialization)

    private val localSnapshot = combine(localRepository.state, localRepository.initialization) { state, initialization ->
        LocalSnapshot(state, initialization)
    }

    val ui: StateFlow<UiState> = combine(
        localSnapshot, phoneFlow, driveFlow, box.status, liveFlow,
    ) { snapshot, phone, drive, link, live ->
        initialUiState(snapshot.state, snapshot.initialization)
            ?: buildUi(checkNotNull(snapshot.state), phone, drive, link, live)
    }.stateIn(scope, SharingStarted.Eagerly, UiState(initialized = false))

    fun retryLocalState() {
        if (!localRepository.retryInitialization()) return
        scope.launch {
            try {
                refreshAfterInitializationRetry(localRepository, { foregroundBoxGate.isForeground }, refreshCoordinator, ::readForRefresh)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // L'écran d'initialisation affiche l'échec de cette tentative.
            }
        }
    }

    // --- Cycle de vie et rafraîchissement

    fun onForeground() {
        foregroundBoxGate.onForeground()
        scope.launch { reconcileProcessing() }
        scope.launch { refreshWhenForeground(refreshCoordinator, { foregroundBoxGate.isForeground }, ::readForRefresh) }
    }

    private suspend fun reconcileProcessing() {
        try { transcriptionCoordinator.reconcile() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { journal.error(Source.APP, "Reprise des traitements impossible : ${reasonOf(e)}") }
        reconcileAutomaticProcessing()
        try { transcriptionStarter.resume(localRepository) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { journal.error(Source.APP, "Reprise du suivi impossible : ${reasonOf(e)}") }
    }

    private suspend fun reconcileAutomaticProcessing() {
        try { automaticProcessingCoordinator.reconcile() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { journal.error(Source.APP, "Programmation automatique impossible : ${reasonOf(e)}") }
    }

    fun onBackground() {
        foregroundBoxGate.onBackground()
        scope.launch {
            foregroundBoxGate.closeIfBackground { if (!liveFlow.value.recovering) box.close() }
        }
    }

    /** Relit le dossier du téléphone, Drive et le boîtier. Ne demande rien au système. Un seul rafraîchissement à la fois. */
    fun refresh() {
        scope.launch { runRefresh(waitForCurrent = false) }
    }

    private suspend fun runRefresh(waitForCurrent: Boolean) {
        refreshCoordinator.run(waitForCurrent, ::readForRefresh)
    }

    private suspend fun readForRefresh() {
        localRepository.awaitInitialized()
        coroutineScope {
            launch { readPhone() }
            launch { readDrive() }
            if (hasBluetooth()) launch { readBoxList() }
        }
    }

    /** Relit la liste du boîtier ; un boîtier qui ne répond pas donne le message « Boîtier hors de portée. ». */
    fun connectBox() {
        if (hasBluetooth()) scope.launch { if (!readBoxList()) say(OUT_OF_RANGE) } else askBluetooth { connectBox() }
    }

    /**
     * Avant chaque recherche du boîtier : lève le drapeau de recherche, puis ferme la liaison de l'app et attend la fin de la fermeture.
     * Un boîtier connecté ne s'annonce plus, donc la recherche ne le verrait pas. Tant que le drapeau est levé, le modèle ne rouvre pas la liaison.
     * Pendant une récupération, la liaison sert au transfert : elle reste ouverte plutôt que d'attendre la fin du transfert.
     */
    suspend fun closeForSearch() {
        searching = true
        if (!liveFlow.value.recovering) box.close()
    }

    /** La feuille de recherche est fermée : baisse le drapeau, le modèle peut rouvrir la liaison. */
    fun endSearch() {
        searching = false
    }

    /**
     * Recherche annulée : baisse le drapeau, puis relit la liste du boîtier ([connectBox]), laissée de côté pendant la recherche.
     * Un [refresh] en cours ferait sauter cette lecture, alors que [connectBox] ne dépend pas du drapeau `refreshing`.
     */
    fun cancelSearch() {
        endSearch()
        connectBox()
    }

    /**
     * Baisse le drapeau de recherche, associe le boîtier, puis lit sa liste sous le même drapeau `refreshing` que [refresh] : la liste vide dit
     * alors qu'elle se lit. `refreshing` monte avant le changement d'adresse et avant la fermeture de la liaison, pour que la liste vide
     * n'affiche jamais « hors de portée » avant la lecture.
     */
    fun associate(name: String, address: String) {
        endSearch()
        val started = refreshCoordinator.tryStart()
        scope.launch {
            try {
                val saved = recoverySafety.withTransferLock {
                    update { it.copy(deviceAddress = address, deviceName = name, boxList = emptyList()) }.await()
                }
                if (saved.isFailure) return@launch
                note("Boîtier associé : $name ($address)")
                box.close()
                if (hasBluetooth()) readBoxList()
            } finally {
                if (started) refreshCoordinator.finish()
            }
        }
    }

    // --- Récupération

    /** [names] nul : tout ce qui manque. [processAfter] enchaîne VTT puis MD après la récupération, même réglage coupé. */
    fun recover(names: List<String>?, processAfter: Boolean = false) {
        scope.launch {
            localRepository.awaitInitialized()
            recoverReady(names, processAfter)
        }
    }

    private fun recoverReady(names: List<String>?, processAfter: Boolean) {
        if (liveFlow.value.recovering) return
        when {
            !hasBluetooth() -> askBluetooth { recover(names, processAfter) }
            localFlow.phoneTreeUri == null -> askPhoneFolder { recover(names, processAfter) }
            else -> scope.launch { recoverNow(names, processAfter) }
        }
    }

    private suspend fun recoverNow(names: List<String>?, processAfter: Boolean) {
        val uri = localFlow.phoneTreeUri?.toUri() ?: return
        val address = localFlow.deviceAddress ?: return
        val entries = recoveryEntries(names)
        if (entries.isEmpty()) return
        val recoveryToken = recoveryDeletionGuard.capture(entries.map { it.name }.toSet()) ?: run {
            say("Suppression en cours, récupération annulée.")
            return
        }
        if (!withContext(Dispatchers.IO) { PhoneFolder.hasAccess(app, uri) }) {
            say(PHONE_FOLDER_LOST)
            return
        }
        if (liveFlow.getAndUpdate { if (it.recovering) it else it.copy(recovering = true, recoveryNames = entries.map { entry -> entry.name }.toSet()) }.recovering) return
        jobs.begin()
        try {
            startWork()
            runRecovery(address, uri, entries, recoveryToken, processAfter)
        } finally {
            jobs.end()
            liveFlow.update { it.copy(recovering = false, recovery = null, recoveryName = null, recoveryNames = emptySet()) }
        }
    }

    private fun recoveryEntries(names: List<String>?): List<ListItem.Entry> {
        val wanted = names?.toSet() ?: buildUi(localFlow, phoneFlow.value, driveFlow.value, box.status.value, liveFlow.value)
            .toRecover.mapTo(mutableSetOf()) { it.name }
        // Un MP3 suivi par une transcription active ne doit pas être écrasé par une récupération.
        val inUse = localFlow.transcriptionTasks.filter { !it.phase.isTerminal() }.mapTo(mutableSetOf()) { it.recordingName }
        return localFlow.boxList.filter { it.name in wanted && it.name !in inUse }
    }

    /**
     * Une connexion qui échoue donne « Boîtier hors de portée. » ; une exception plus tard marque l'enregistrement en cours.
     * La liaison se ferme après un échec qui arrête la file, puis à la fin si l'app est en arrière-plan.
     */
    private suspend fun runRecovery(address: String, uri: Uri, entries: List<ListItem.Entry>, recoveryToken: Map<String, Long>, processAfter: Boolean) {
        var connected = false
        val outcome = withRecoveryFinalization({ connected }, { reconcileAutomaticProcessing() }) {
            attempt {
                withContext(Dispatchers.IO) {
                    recoverySafety.withTransferLock {
                        if (localFlow.deviceAddress != address) throw RecoveryGuardException(IllegalStateException("Le boîtier a changé."))
                        box.withSession(address) { session ->
                            connected = true
                            RecoveryJob(session, PhoneFolder(app, uri), File(app.cacheDir, "recovery"), journal)
                                .run(entries, ::publishRecovery, { readPhone() },
                                    beforeRecovery = { entry ->
                                        if (!recoveryDeletionGuard.isCurrent(recoveryToken, entry.name) ||
                                            localFlow.phoneTreeUri != uri.toString() || recoveryEntries(names = listOf(entry.name)).none { it == entry })
                                            throw RecoveryGuardException(IllegalStateException("La sélection ou le dossier a changé."))
                                        recoverySafety.begin(entry.name, uri.toString(), processAfter)
                                    },
                                    validateResult = { result -> recoverySafety.finish(result, uri.toString()) })
                        }
                    }
                }
            }
        }
        outcome.onSuccess { results -> if ((results.lastOrNull() as? RecoveryResult.Failed)?.stopsQueue == true) box.close() }
        outcome.onFailure { e ->
            if (e is RecoveryGuardException) {
                val message = reasonOf(e)
                say(message)
                journal.error(Source.FS, message)
            } else if (connected) markRecoveryFailed(e, uri.toString()) else say(OUT_OF_RANGE)
        }
        readPhone()
        if (!foregroundBoxGate.isForeground) box.close()
    }

    /** Publie l'avancement quand son libellé change : le boîtier notifie tous les 244 octets environ. */
    private fun publishRecovery(name: String, progress: Progress) {
        val current = liveFlow.value
        if (current.recoveryName == name && current.recovery?.label() == progress.label()) return
        liveFlow.update { it.copy(recovery = progress, recoveryName = name) }
    }

    private suspend fun markRecoveryFailed(e: Throwable, folderUri: String) {
        liveFlow.value.recoveryName?.let { name ->
            recoverySafety.withTransferLock {
                recoverySafety.finish(RecoveryResult.Failed(name, reasonOf(e), true), folderUri)
            }
        }
    }

    private fun deletionProtectedNames(local: LocalState): Set<String> {
        val live = liveFlow.value
        return deletionProtectedNames(local, live.recoveryNames, live.sendNames)
    }

    /** Suppression du dossier du téléphone, sous le verrou fourni : un appelant qui tient déjà le verrou de transfert passe un verrou transparent. */
    private fun phoneDeletionOrchestrator(
        folderUri: String, recordings: List<Recording>,
        withTransferLock: suspend (suspend () -> DeletionOutcome) -> DeletionOutcome,
    ) = PhoneDeletionOrchestrator(
        withTransferLock = withTransferLock,
        withPhoneLock = { block -> phoneMutex.withLock { block() } },
        snapshot = {
            val local = localRepository.awaitInitialized()
            val folder = local.phoneTreeUri
            if (folder != null && folder == folderUri) {
                val fresh = withContext(Dispatchers.IO) { readFolder(folder.toUri(), phoneFlow.value) }
                phoneFlow.value = fresh
                if (!fresh.ok) throw PhoneStoreException(PHONE_FOLDER_LOST)
            }
            val current = buildUi(local, phoneFlow.value, driveFlow.value, box.status.value, liveFlow.value)
            DeletionSnapshot(folder, current.recordings, deletionProtectedNames(local), current.drive, local, current.documentHashes)
        },
        delete = { plan -> withContext(Dispatchers.IO) { PhoneDeletion.execute(plan, PhoneFolder(app, plan.folderUri.toUri())) } },
        reread = { result ->
            val folder = localRepository.awaitInitialized().phoneTreeUri
            if (folder != folderUri) throw PhoneStoreException("Le dossier du téléphone a changé.")
            val fresh = withContext(Dispatchers.IO) { readFolder(folder.toUri(), phoneFlow.value) }.afterPromotion()
            phoneFlow.value = fresh
            if (!fresh.ok) throw PhoneStoreException("Lecture du dossier après suppression impossible.")
            localRepository.updateDurably { state ->
                recordings.fold(state) { current, recording ->
                    val removed = recording.phoneFiles.values.any { it.fileName in result.deleted }
                    if (!removed) current else {
                        var cleaned = current.withoutError(recording.name, Step.SEND)
                        if (recording.phoneFiles[FileKind.MP3]?.fileName in result.deleted)
                            cleaned = cleaned.withoutError(recording.name, Step.RECOVER)
                        if (recording.phoneFiles[FileKind.VTT]?.fileName in result.deleted ||
                            recording.phoneFiles[FileKind.MP3]?.fileName in result.deleted)
                            cleaned = cleaned.withoutError(recording.name, Step.TRANSCRIBE)
                        cleaned
                    }
                }
            }
        },
    )

    // --- Suppression unifiée

    /**
     * Prépare la confirmation de « Supprimer partout » ou « Libérer l'espace » pour [names].
     * Aucune fenêtre sans cible. Le Bluetooth n'est demandé que si un niveau boîtier est visé.
     */
    fun requestDeletion(mode: DeletionMode, names: Set<String>) {
        val local = localRepository.state.value ?: return
        if (liveFlow.value.deleting) return
        val phone = phoneFlow.value
        val knownPhone = if (phone.ok && local.phoneTreeUri != null && phone.folderUri == local.phoneTreeUri) phone else phone.copy(files = emptyList())
        val current = buildUi(local, knownPhone, driveFlow.value, box.status.value, liveFlow.value)
        // En « Supprimer partout », la liste Drive brute dit si Drive désactivé laisse des copies intactes.
        val drive = if (mode == DeletionMode.EVERYWHERE) driveFlow.value else driveFlow.value.takeIf { local.driveEnabled }
        val plan = UnifiedDeletion.plan(mode, current.recordings, names, deletionProtectedNames(local), drive, local,
            local.deviceAddress, local.phoneTreeUri)
        if (plan.targets.isEmpty()) {
            liveFlow.update { it.copy(notice = "Rien à supprimer.") }
            return
        }
        requestBoxDeletionWithPermission(plan.targets.none { it.box } || hasBluetooth(), { askBluetooth { requestDeletion(mode, names) } }) {
            synchronized(deletionRequestLock) {
                if (!liveFlow.value.deleting) liveFlow.update { it.copy(deletionRequest = plan) }
            }
        }
    }

    /**
     * Glissement vers la gauche sur une tuile : récupère puis traite, transcrit ce qui manque, ou dit pourquoi rien ne part.
     */
    fun processOne(name: String) {
        val local = localRepository.state.value ?: return
        val current = buildUi(local, phoneFlow.value, driveFlow.value, box.status.value, liveFlow.value)
        val recording = current.recordings.firstOrNull { it.name == name } ?: return
        val action = swipeProcessing(
            recording,
            busy = name in deletionProtectedNames(local),
            hasVtt = FileKind.VTT in recording.phoneFiles,
            hasMd = FileKind.MD in recording.phoneFiles,
        )
        when (action) {
            SwipeProcessing.RECOVER ->
                if (liveFlow.value.recovering) liveFlow.update { it.copy(notice = "Traitement en cours") }
                else recover(listOf(name), processAfter = true)
            SwipeProcessing.TRANSCRIBE -> transcribe(setOf(name))
            SwipeProcessing.DONE -> liveFlow.update { it.copy(notice = "Déjà traité") }
            SwipeProcessing.BUSY -> liveFlow.update { it.copy(notice = "Traitement en cours") }
        }
    }

    fun cancelDeletion() = synchronized(deletionRequestLock) {
        liveFlow.update { it.copy(deletionRequest = null) }
    }

    fun dismissNotice() = liveFlow.update { it.copy(notice = null) }

    fun confirmDeletion() {
        val plan = synchronized(deletionRequestLock) {
            val live = liveFlow.value
            val request = live.deletionRequest ?: return
            if (live.deleting || !unifiedDeletionCanConfirm(request, false)) return
            liveFlow.update { it.copy(deletionRequest = null, deleting = true, notice = null) }
            request
        }
        jobs.begin()
        scope.launch {
            try {
                startWork()
                runUnifiedDeletion(plan)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                journal.error(Source.APP, "suppression : ${reasonOf(e)}")
                liveFlow.update { it.copy(notice = "Suppression incomplète : ${reasonOf(e)} (voir le journal)") }
            } finally {
                synchronized(deletionRequestLock) { liveFlow.update { it.copy(deleting = false) } }
                jobs.end()
            }
        }
    }

    /**
     * Toute l'exécution tient le verrou de transfert et la garde de suppression des cibles : un envoi admis avant la capture
     * rend le plan bloqué. Les niveaux appellent donc les pièces de bas niveau sans reprendre le verrou de transfert.
     */
    private suspend fun runUnifiedDeletion(plan: UnifiedDeletionPlan) {
        val names = plan.targets.map { it.name }.toSet()
        var outcome: UnifiedDeletionOutcome? = null
        var busyNames = emptySet<String>()
        recoverySafety.withTransferLock {
            recoveryDeletionGuard.withDeletionSnapshot(names) { admittedSends ->
                val local = localRepository.awaitInitialized()
                busyNames = unifiedDeletionBusyNames(names, deletionProtectedNames(local), admittedSends)
                if (busyNames.isEmpty()) outcome = unifiedDeletionOrchestrator(plan).execute(plan)
            }
        }
        if (busyNames.isNotEmpty()) {
            liveFlow.update { it.copy(notice = "Enregistrements occupés : ${busyNames.sorted().joinToString()}") }
            return
        }
        val result = outcome ?: return
        if (plan.targets.any { it.phone }) readPhone()
        if (plan.targets.any { it.driveFiles.isNotEmpty() }) readDrive()
        result.failures.forEach { failure -> journal.error(failureSource(failure), failure) }
        liveFlow.update { it.copy(notice = unifiedDeletionFailureText(result.failures)) }
    }

    private fun failureSource(failure: String): Source = when {
        failure.startsWith("Drive") -> Source.DRIVE
        failure.startsWith("Téléphone") -> Source.FS
        failure.startsWith("Boîtier") -> Source.BLE
        else -> Source.APP
    }

    private fun unifiedDeletionOrchestrator(plan: UnifiedDeletionPlan) = UnifiedDeletionOrchestrator(
        trashDrive = { file -> withContext(Dispatchers.IO) { driveClient().trash(file.id) } },
        deletePhone = { phoneNames ->
            val folder = plan.folderUri ?: throw PhoneStoreException(PHONE_FOLDER_LOST)
            val recordings = buildUi(localFlow, phoneFlow.value, driveFlow.value, box.status.value, liveFlow.value)
                .recordings.filter { it.name in phoneNames && it.phoneFiles.isNotEmpty() }
            val runner = phoneDeletionOrchestrator(folder, recordings) { block -> block() }
            when (val result = runner.execute(folder, phoneNames)) {
                DeletionOutcome.FolderChanged, is DeletionOutcome.Blocked -> null
                is DeletionOutcome.Completed ->
                    if (result.readError == null) result.result
                    else result.result.copy(failures = result.result.failures + ("dossier" to result.readError))
            }
        },
        deleteBox = { boxNames ->
            val address = plan.boxAddress
            if (address == null || localRepository.awaitInitialized().deviceAddress != address) throw IllegalStateException(BOX_OUT_OF_RANGE)
            withContext(Dispatchers.IO) {
                var connected = false
                val result = try {
                    box.withSession(address) { session ->
                        connected = true
                        BoxDeletionJob(session::deleteRecord, session::listRecords, { entries ->
                            localRepository.updateDurably { state ->
                                if (state.deviceAddress == address) state.copy(boxList = entries) else state
                            }
                        }).run(boxNames)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (connected) throw e else throw IllegalStateException(BOX_OUT_OF_RANGE, e)
                }
                if (result.error != null || !foregroundBoxGate.isForeground) box.close()
                result
            }
        },
        onDriveTrashed = { ids -> localRepository.updateDurably { it.withoutDriveReceipts(ids) } },
    )

    // --- Envoi vers Drive

    /**
     * [names] nul : tout ce qui manque dans Drive si sa liste est connue, sinon tout ce qui a un fichier sur le téléphone.
     * Sans envoi vers Drive activé, rien ne part.
     */
    fun send(names: List<String>?, onConfirmed: (() -> Unit)? = null) {
        val local = localRepository.state.value ?: return
        synchronized(driveSendRequests) {
            if (!local.driveEnabled || liveFlow.value.sending) return
            driveSendRequests.open(names, local.driveSendChoice, onConfirmed)
            liveFlow.update { it.copy(driveSendRequest = driveSendRequests.current) }
        }
    }

    fun cancelDriveSend() = synchronized(driveSendRequests) {
        driveSendRequests.cancel()
        liveFlow.update { it.copy(driveSendRequest = null) }
    }

    fun confirmDriveSend(choice: DriveSendChoice, displayedNames: Set<String>) {
        synchronized(driveSendRequests) {
            if (driveSendRequests.current == null) return
            val admission = recoveryDeletionGuard.admitSend(displayedNames) ?: run {
                say("Suppression en cours, envoi annulé.")
                return
            }
            val request = try { driveSendRequests.confirm(choice) } catch (e: Throwable) {
                admission.close()
                throw e
            } ?: run { admission.close(); return }
            liveFlow.update { it.copy(driveSendRequest = null, sending = true, sendNames = displayedNames.toSet()) }
            val folder = localFlow.phoneTreeUri
            scope.launch { sendNow(request, folder, displayedNames) }.invokeOnCompletion { admission.close() }
        }
    }

    /**
     * Lance la transcription de [names] (null : tout ce qui est éligible). La planification WorkManager est confirmée avant
     * la tâche durable et le service ; la boucle interactive suit ensuite les tâches jusqu'à leur fin ou leur suspension.
     */
    fun transcribe(names: Set<String>?, request: RequestedWork = RequestedWork.MISSING, resumeRecording: Boolean = false) {
        val job = launchProcessingRequest(scope, recoveryDeletionGuard, names) {
            jobs.track {
                try {
                    if (request == RequestedWork.MISSING) announceExclusions(names)
                    logTranscriptionRequest(names, request)
                    val started = transcriptionCoordinator.start(names, request, resumeRecording)
                    if (started.isEmpty()) reportUnconfiguredSynthesis(names, request)
                    if (started.isNotEmpty()) transcriptionLifecycle.request().join()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // L'erreur est écrite dans la source de la demande (visible sous les filtres) et en APP.
                    journal.error(if (request == RequestedWork.REDO_MD) Source.LLM else Source.STT, "demande : ${reasonOf(e)}")
                    journal.error(Source.APP, "transcription : ${reasonOf(e)}")
                    say(reasonOf(e))
                }
            }
        }
        if (job == null) say("Suppression en cours, traitement annulé.")
    }

    /** Chaque enregistrement concerné laisse une ligne « demande reçue » ou « demande écartée », en source STT ou LLM. */
    private suspend fun logTranscriptionRequest(names: Set<String>?, request: RequestedWork) {
        val state = localRepository.awaitInitialized()
        val current = buildUi(state, phoneFlow.value, driveFlow.value, box.status.value, liveFlow.value)
        val active = state.transcriptionTasks.filter { !it.phase.isTerminal() }.mapTo(mutableSetOf()) { it.recordingName }
        for (event in requestJournalEvents(current.recordings, names, state.errors, active, request)) {
            val line = event.name?.let { "$it : ${event.message}" } ?: event.message
            journal.add(event.source, line)
        }
    }

    /**
     * Rien n'est parti et un enregistrement n'attendait qu'un MD : la synthèse n'est pas configurée.
     * Le message dit quoi régler et où ; une ligne LLM par enregistrement concerne le journal.
     */
    private suspend fun reportUnconfiguredSynthesis(names: Set<String>?, request: RequestedWork) {
        if (request == RequestedWork.REDO_VTT_AND_MD) return
        val state = localRepository.awaitInitialized()
        val current = buildUi(state, phoneFlow.value, driveFlow.value, box.status.value, liveFlow.value)
        val active = state.transcriptionTasks.filter { !it.phase.isTerminal() }.mapTo(mutableSetOf()) { it.recordingName }
        val (plans, _) = SynthesisSelection.plan(current.recordings, names, state.errors, active, request)
        val waiting = plans.filter { !it.makeVtt && it.makeMd }.map { it.recording }
        if (waiting.isEmpty()) return
        for (recording in waiting) {
            val base = recording.phoneFiles[FileKind.MP3]?.fileName?.removeSuffix(".mp3") ?: recording.name
            journal.add(Source.LLM, "$base : réglages de synthèse à compléter")
        }
        say("Réglages de synthèse à compléter. Ouvre Réglages, rubrique Synthèse.")
    }

    /** Signale les éléments choisis qui ne seront pas transcrits : sans MP3 sur le téléphone ou en erreur de récupération. */
    private suspend fun announceExclusions(names: Set<String>?) {
        val state = localRepository.awaitInitialized()
        val current = buildUi(state, phoneFlow.value, driveFlow.value, box.status.value, liveFlow.value)
        val active = state.transcriptionTasks.filter { !it.phase.isTerminal() }.mapTo(mutableSetOf()) { it.recordingName }
        synthesisSelectionMessage(current.recordings, names, state.errors, active)?.let { say(it) }
    }

    private suspend fun followTranscriptions(excluded: MutableSet<String>): InteractiveRun {
        val scheduler = TranscriptionSchedule(androidx.work.WorkManager.getInstance(app))
        val runner = TranscriptionWorkerRunner(localRepository, transcriptionCoordinator, scheduler::cancel)
        return TranscriptionInteractiveRunner(localRepository, transcriptionCoordinator, runner).run(excluded)
    }

    /** Demande l'annulation de la transcription de [name] ; l'intention est persistée avant tout appel au serveur. */
    fun cancelTranscription(name: String) {
        scope.launch {
            val task = localRepository.awaitInitialized().transcriptionTasks
                .firstOrNull { it.recordingName == name && !it.phase.isTerminal() } ?: return@launch
            try {
                transcriptionCoordinator.requestCancel(task.localId)
                val scheduler = TranscriptionSchedule(androidx.work.WorkManager.getInstance(app))
                TranscriptionWorkerRunner(localRepository, transcriptionCoordinator, scheduler::cancel).retireIfTerminal(task.localId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                journal.error(Source.APP, "annulation de transcription : ${reasonOf(e)}")
                say(reasonOf(e))
            }
        }
    }

    /** Reprend la tâche de synthèse suspendue sans changer son contexte figé. */
    fun retrySynthesis(localId: String) {
        scope.launch {
            jobs.track {
                try {
                    transcriptionCoordinator.retrySynthesis(localId)
                    transcriptionLifecycle.request().join()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    journal.error(Source.LLM, "reprise de synthèse : ${reasonOf(e)}")
                    say(reasonOf(e))
                }
            }
        }
    }

    /** Cesse le suivi interactif (timeout du service) ; le travail périodique déjà programmé prend le relais. */
    fun stopInteractiveTranscription() {
        transcriptionLifecycle.stop()
    }

    private suspend fun sendNow(request: DriveSendRequest, folder: String?, displayedNames: Set<String>) {
        jobs.begin()
        try {
            if (update { it.copy(driveSendChoice = request.choice) }.await().isFailure) {
                say("Impossible d'enregistrer le choix Drive, envoi annulé.")
                return
            }
            if (!authorizeOrAsk { send(request.names) }) return
            recoverySafety.withTransferLock {
                val captured = localRepository.awaitInitialized()
                if (captured.phoneTreeUri != folder) return@withTransferLock
                val uri = captured.phoneTreeUri?.toUri() ?: return@withTransferLock
                val account = captured.driveAccount?.takeIf { it.isNotBlank() } ?: run {
                    say("Adresse du compte Google indisponible, envoi annulé.")
                    return@withTransferLock
                }
                readPhone()
                if (!phoneFlow.value.ok) { say(PHONE_FOLDER_LOST); return@withTransferLock }
                val current = buildUi(captured, phoneFlow.value, driveFlow.value, box.status.value, liveFlow.value)
                val safeRecordings = DriveSync.confirmedSendable(current.recordings, current.drive, captured,
                    current.documentHashes, request, request.choice, displayedNames)
                if (safeRecordings.isEmpty()) return@withTransferLock
                startWork()
                withContext(Dispatchers.IO) { sendRecordings(uri, safeRecordings, request, captured, account) }
                readDrive()
            }
        } finally {
            jobs.end()
            liveFlow.update { it.copy(sending = false, send = null, sendName = null, sendNames = emptySet()) }
        }
    }

    /** Le compte, le dossier et les enregistrements proviennent de la confirmation. */
    private suspend fun sendRecordings(uri: Uri, recordings: List<Recording>, request: DriveSendRequest, captured: LocalState, account: String) {
        val drive = driveClientForCapturedAccount(LoggingHttp(UrlHttp(), { journal.add(Source.DRIVE, it) }), account, auth::tokens)
        val folder = try {
            val (folder, resolved) = resolveFolder(captured, drive)
            if (captured.chosenFolderId == null && update {
                    if (it.chosenFolderId == captured.chosenFolderId && it.driveAccount == captured.driveAccount) it.withFoldersOf(resolved) else it
                }.await().isFailure) return
            folder
        } catch (e: Exception) {
            val message = folderMessage(e)
            journal.error(Source.DRIVE, message)
            say(message)
            return
        }
        val outcome = SendJob(drive, PhoneFolder(app, uri), journal, app.cacheDir)
            .run(folder, recordings, request.choice, DriveDestination(account, folder.id), captured.driveReceipts, ::publishSend, { result ->
                if (result is SendResult.Sent && result.receipt != null) {
                    localRepository.updateDurably { it.withDriveReceipt(result.receipt) }
                }
            }, explicitSelection = request.names != null, beforeUpdate = { destination, fileName ->
                localRepository.updateDurably { it.withoutDriveReceipt(destination, fileName) }
            })
        update { applySendOutcome(it, outcome) }
        if (outcome is SendOutcome.Stopped) say(outcome.reason)
    }

    private fun publishSend(name: String, progress: Progress) {
        val current = liveFlow.value
        if (current.sendName == name && current.send?.label() == progress.label()) return
        liveFlow.update { it.copy(send = progress, sendName = name) }
    }

    /** Relance l'étape de l'erreur affichée, la première dans l'ordre de [Step]. */
    fun retry(name: String) {
        scope.launch {
            localRepository.awaitInitialized()
            val step = localFlow.errors[name]?.keys?.minByOrNull { it.ordinal }
            when {
                step == Step.RECOVER -> recover(listOf(name))
                step == Step.SEND -> send(listOf(name))
            }
        }
    }

    // --- Réponses du système

    fun onBluetoothPermission(granted: Boolean) {
        val resume = afterBluetooth.also { afterBluetooth = null }
        if (granted && hasBluetooth()) resume?.invoke() else say("Les permissions Bluetooth sont nécessaires")
    }

    /**
     * Émet la demande de dossier ; elle sert aussi aux Réglages (« Changer »), sans action en attente.
     * Ignorée pendant une récupération ou un envoi : changer de dossier relâcherait le droit que le travail en cours utilise.
     * Un dossier reçu pendant un travail est ignoré de la même façon par [onPhoneFolderPicked].
     */
    fun choosePhoneFolder() {
        if (activeJobs > 0) return
        ask(UiRequest.PickPhoneFolder)
    }

    fun onPhoneFolderPicked(uri: Uri?) {
        val resume = afterPhoneFolder.also { afterPhoneFolder = null }
        if (uri == null) {
            say(NO_FOLDER)
            return
        }
        if (activeJobs > 0) {
            say(WAIT_FOR_TRANSFER)
            return
        }
        scope.launch {
            if (!recoverySafety.withTransferLock { keepPhoneFolder(uri) }) return@launch
            readPhone()
            resume?.invoke()
        }
    }

    /** Retient le droit sur le nouveau dossier, puis relâche celui de l'ancien. Faux si le système refuse. Les fichiers de l'ancien dossier restent en place. */
    private suspend fun keepPhoneFolder(uri: Uri): Boolean {
        localRepository.awaitInitialized()
        val previous = localFlow.phoneTreeUri
        try {
            withContext(Dispatchers.IO) { PhoneFolder.keep(app, uri) }
        } catch (e: SecurityException) {
            journal.error(Source.FS, PHONE_FOLDER_LOST)
            say(PHONE_FOLDER_LOST)
            return false
        }
        if (update { it.copy(phoneTreeUri = uri.toString()) }.await().isFailure) {
            if (previous != uri.toString()) withContext(Dispatchers.IO) { releasePhoneFolder(uri.toString()) }
            return false
        }
        journal.add(Source.FS, "Dossier du téléphone : ${PhoneFolder.displayPath(uri)}")
        if (previous != null && previous != uri.toString()) {
            journal.add(Source.APP, "Dossier du téléphone changé : ${displayPathOf(previous)} vers ${PhoneFolder.displayPath(uri)}")
            phoneFlow.value = PhoneSnapshot()
            withContext(Dispatchers.IO) { releasePhoneFolder(previous) }
        }
        return true
    }

    /** Chemin affiché d'un dossier gardé sous forme d'adresse ; l'adresse elle-même si elle ne se lit pas. */
    private fun displayPathOf(treeUri: String): String = runCatching { PhoneFolder.displayPath(treeUri.toUri()) }.getOrDefault(treeUri)

    private fun releasePhoneFolder(treeUri: String) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { app.contentResolver.releasePersistableUriPermission(treeUri.toUri(), flags) }
    }

    fun onGoogleResult(data: Intent?) {
        val resume = afterGoogle.also { afterGoogle = null }
        if (!auth.granted(data)) {
            say("Autorisation Google non accordée")
            return
        }
        resume?.invoke()
    }

    // --- Google Drive

    /** L'envoi n'est utile qu'avec l'accord Google : l'activer le demande tout de suite. */
    fun setDriveEnabled(on: Boolean) {
        val saved = update { if (on) it.copy(driveEnabled = true) else it.copy(driveEnabled = false).withoutErrors(Step.SEND) }
        scope.launch {
            if (saved.await().isFailure) return@launch
            note(if (on) "Envoi vers Drive activé" else "Envoi vers Drive désactivé")
            if (!on) {
                driveFlow.value = null
                return@launch
            }
            if (!authorizeOrAsk { setDriveEnabled(true) }) return@launch
            if (localFlow.chosenFolderId == null) rememberAccount()
            readDrive()
        }
    }

    fun chooseFolder(id: String) {
        val saved = update { it.copy(chosenFolderId = id) }
        scope.launch {
            val state = saved.await().getOrNull() ?: return@launch
            note("Dossier Drive choisi : ${state.knownFolders.firstOrNull { it.id == id }?.name ?: id}")
            folderChanged()
        }
    }

    fun useDefaultFolder() {
        driveFolderAction({ useDefaultFolder() }, ::folderMessage) { drive ->
            resolveFolder(localFlow.copy(chosenFolderId = null), drive).second
        }
    }

    fun createFolder(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        driveFolderAction({ createFolder(name) }, ::folderMessage) { drive ->
            val folder = drive.createFolder(clean)
            val known = localFlow.knownFolders.filter { it.id != folder.id } + KnownFolder(folder.id, folder.name, FolderOrigin.CREATED)
            localFlow.copy(knownFolders = known, chosenFolderId = folder.id)
        }
    }

    fun openPicker() {
        scope.launch {
            val intent = attempt { auth.picker() }
                .onFailure { journal.error(Source.DRIVE, "sélecteur : ${reasonOf(it)}") }
                .getOrNull()
            if (intent == null) say("Sélecteur Drive indisponible") else ask(UiRequest.GooglePicker(intent))
        }
    }

    /** Le contrôle du dossier choisi, son ajout aux dossiers connus (origine [FolderOrigin.PICKED]) et son choix sont ceux de [resolveFolder]. */
    fun onPickerResult(data: Intent?) {
        val id = auth.picked(data)
        if (id == null) {
            say(NO_FOLDER)
            return
        }
        driveFolderAction({ onPickerResult(data) }, { "Dossier refusé : ${reasonOf(it)}" }) { drive ->
            resolveFolder(localFlow.copy(chosenFolderId = id), drive).second
        }
    }

    /** Dossier connu → accessible. Sans accord Google, aucun ne l'est. */
    suspend fun folderAccess(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        localRepository.awaitInitialized()
        val folders = localFlow.knownFolders
        if (folders.isEmpty()) return@withContext emptyMap()
        val drive = driveClient()
        folders.associate { it.id to attempt { drive.checkFolder(it.id) }.isSuccess }
    }

    /**
     * Révoque l'accès, puis efface le compte et désactive l'envoi, même si Google n'a pas répondu : l'erreur est au journal.
     * L'adresse est relue dans Drive, car celle qui est gardée peut venir d'un autre compte ; si la lecture échoue, la gardée sert.
     * Sans aucune adresse, l'accès n'est pas révoqué.
     */
    fun disconnectGoogle() {
        scope.launch {
            localRepository.awaitInitialized()
            val account = readAccount() ?: localFlow.driveAccount
            if (account == null) {
                journal.add(Source.DRIVE, "Compte Google inconnu, accès non révoqué")
            } else {
                attempt { auth.revoke(account) }.onFailure { journal.error(Source.DRIVE, "révocation : ${reasonOf(it)}") }
            }
            if (update { it.copy(driveAccount = null, driveEnabled = false).withoutErrors(Step.SEND) }.await().isSuccess) {
                driveFlow.value = null
            }
        }
    }

    // --- Divers

    fun setTheme(theme: ThemeChoice) { appLocalState.setTheme(theme) }

    fun setAutoProcessAfterRecovery(enabled: Boolean) { update { it.copy(autoProcessAfterRecovery = enabled) } }

    val transcriptionForm = TranscriptionSettingsForm(scope) { settings, secret ->
        withContext(Dispatchers.IO) { transcriptionSettings.save(settings, secret) }
        reconcileAutomaticProcessing()
    }
    val synthesisForm = SynthesisSettingsForm(scope,
        save = { settings, secret -> withContext(Dispatchers.IO) { synthesisSettings.saveService(settings, secret) } },
        test = { settings, secret -> withContext(Dispatchers.IO) { synthesisSettings.test(settings, secret, synthesisClient) } },
        savePrompt = { prompt -> withContext(Dispatchers.IO) { synthesisSettings.savePrompt(prompt) } },
    )

    /** Adresse du fichier dans le dossier du téléphone, null s'il est absent ou inaccessible. Lit le dossier : à appeler hors du fil principal. */
    suspend fun phoneUri(fileName: String): Uri? {
        localRepository.awaitInitialized()
        return localFlow.phoneTreeUri?.let { PhoneFolder(app, it.toUri()).uriOf(fileName) }
    }

    /** Segments du VTT affichés sur la fiche. Le fichier n'est jamais modifié ; échec : message lisible. À appeler hors du fil principal. */
    suspend fun readTranscript(fileName: String): Result<List<VttCue>> {
        localRepository.awaitInitialized()
        val tree = localFlow.phoneTreeUri ?: return Result.failure(PhoneStoreException(PHONE_FOLDER_LOST))
        return try {
            Result.success(WebVtt.parse(PhoneFolder(app, tree.toUri()).open(fileName).use { it.readBytes() }))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Lit le MD et vérifie sa provenance depuis le dossier courant, hors du fil principal. */
    suspend fun readSynthesis(mdName: String, vttName: String?): Result<SynthesisDocument> {
        val state = localRepository.awaitInitialized()
        val tree = state.phoneTreeUri ?: return Result.failure(PhoneStoreException(PHONE_FOLDER_LOST))
        return try {
            val folder = PhoneFolder(app, tree.toUri())
            val md = folder.open(mdName).use { it.readBytes() }
            val mdHash = java.security.MessageDigest.getInstance("SHA-256").digest(md).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val vttHash = vttName?.let { runCatching { TranscriptionAudio.sha256(folder, it) }.getOrNull() }
            val provenance = state.synthesisProvenance.firstOrNull { it.folderUri == tree && it.mdName == mdName }
            val status = provenanceStatus(provenance, tree, vttHash, mdHash)
            Result.success(SynthesisDocument(md.toString(Charsets.UTF_8), status, if (status == ProvenanceStatus.UNKNOWN) null else provenance?.model))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun consumeMessage() = liveFlow.update { it.copy(message = null) }

    // --- Lectures

    private suspend fun readPhone() {
        localRepository.awaitInitialized()
        phoneMutex.withLock {
            val uri = localFlow.phoneTreeUri?.toUri()
            phoneFlow.value = if (uri == null) PhoneSnapshot() else withContext(Dispatchers.IO) { readFolder(uri, phoneFlow.value) }
        }
    }

    private suspend fun readPhoneAfterPromotion(folder: String) {
        localRepository.awaitInitialized()
        phoneMutex.withLock {
            if (localFlow.phoneTreeUri != folder) return@withLock
            val snapshot = try {
                withContext(Dispatchers.IO) { readFolder(folder.toUri(), phoneFlow.value) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                journal.error(Source.FS, "Lecture du dossier après promotion : ${reasonOf(e)}")
                phoneFlow.value.copy(ok = false, documentHashes = emptyMap())
            }
            if (localFlow.phoneTreeUri == folder) phoneFlow.value = snapshot.afterPromotion()
        }
    }

    /** Fichiers du dossier ; s'il ne répond pas, la dernière lecture marquée inaccessible, avec l'erreur au journal. */
    private fun readFolder(uri: Uri, last: PhoneSnapshot): PhoneSnapshot =
        try {
            if (!PhoneFolder.hasAccess(app, uri)) throw PhoneStoreException(PHONE_FOLDER_LOST)
            val folder = PhoneFolder(app, uri)
            val files = folder.list()
            val hashes = readDocumentHashes(files, { TranscriptionAudio.sha256(folder, it) }) {
                journal.error(Source.FS, "Lecture du document : ${reasonOf(it)}")
            }
            last.copy(files = files, ok = true, documentHashes = hashes, folderUri = uri.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            journal.error(Source.FS, if (e is PhoneStoreException) e.journalText else reasonOf(e))
            last.copy(ok = false, documentHashes = emptyMap())
        }

    /**
     * La liste du boîtier remplace `boxList` ; un échec garde la dernière, et [BoxConnection] l'a déjà noté au journal.
     * Faux si le boîtier n'a pas répondu ; vrai si la liste est lue, ou si rien n'est à lire (aucun boîtier, récupération ou recherche en cours).
     */
    private suspend fun readBoxList(): Boolean = recoverySafety.withTransferLock {
        readBoxListForForeground(foregroundBoxGate, localRepository, { liveFlow.value.recovering || searching })
            { address -> attempt { box.list(address) } }
    }

    /**
     * Sans accord Google, la liste reste nulle et une ligne DRIVE le note. Un échec de lecture garde la dernière liste.
     * Avec l'accord, l'adresse du compte est lue après la liste et hors du verrou : un Drive lent ne retient pas les autres lectures.
     */
    private suspend fun readDrive() {
        localRepository.awaitInitialized()
        if (driveMutex.withLock { readDriveLocked() }) rememberAccount()
    }

    /** Lit la liste du dossier choisi sous le verrou de [readDrive]. Vrai si l'accord Google est donné. */
    private suspend fun readDriveLocked(): Boolean {
        val state = localFlow
        val folderId = state.chosenFolderId
        if (!state.driveEnabled || folderId == null) {
            driveFlow.value = null
            return false
        }
        val granted = attempt { auth.authorize() }.getOrElse { e ->
            journal.error(Source.DRIVE, "accord Google : ${reasonOf(e)}")
            return false
        }
        return when (granted) {
            is AuthState.NeedsUser -> {
                driveFlow.value = null
                journal.add(Source.DRIVE, "Accord Google à donner, liste du dossier Drive non lue")
                false
            }
            is AuthState.Granted -> {
                readListing(folderId)
                true
            }
        }
    }

    private suspend fun readListing(folderId: String) {
        attempt { withContext(Dispatchers.IO) { driveClient().listEntries(folderId) } }
            .onSuccess { entries -> if (localFlow.chosenFolderId == folderId) driveFlow.value = DriveListing(entries.associate { it.name to it.size }, entries) }
            .onFailure { journal.error(Source.DRIVE, "lecture du dossier Drive : ${reasonOf(it)}") }
    }

    // --- Aides

    /**
     * Applique et enregistre [change] par le même dépôt que les futures écritures durables.
     * Le dépôt publie l'état seulement après la réussite de l'écriture.
     */
    private fun update(change: (LocalState) -> LocalState) = localRepository.update(change)

    private fun say(message: String) = liveFlow.update { it.copy(message = message) }

    /**
     * Une action de l'utilisateur au journal, source APP, écrite hors du fil principal et dans l'ordre des actions.
     * Un journal illisible ou un disque plein ne lève rien ([Journal.add]) : la ligne manque, l'action a eu lieu.
     */
    private fun note(message: String) {
        scope.launch(saver) { journal.add(Source.APP, message) }
    }

    private fun ask(request: UiRequest) {
        requestFlow.tryEmit(request)
    }

    private fun askBluetooth(resume: () -> Unit) {
        afterBluetooth = resume
        ask(UiRequest.BluetoothPermission)
    }

    private fun askPhoneFolder(resume: () -> Unit) {
        afterPhoneFolder = resume
        ask(UiRequest.PickPhoneFolder)
    }

    private fun hasBluetooth(): Boolean = BLUETOOTH_PERMISSIONS.all { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Démarre le service de premier plan, et demande l'autorisation des notifications une fois par lancement de l'app. */
    private fun startWork() {
        WorkService.start(app)
        val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needed && notificationAsked.compareAndSet(false, true)) ask(UiRequest.NotificationPermission)
    }

    /**
     * Vrai si l'accord Google est donné. Sinon, la demande d'accord est émise et [resume] reprendra l'action, ou l'erreur est notée avec un message.
     * Un délai dépassé donne le message du délai, pas celui du renouvellement de l'accès.
     */
    private suspend fun authorizeOrAsk(resume: () -> Unit): Boolean {
        val state = attempt { auth.authorize() }.getOrElse { e ->
            journal.error(Source.DRIVE, "accord Google : ${reasonOf(e)}")
            say(if (e is TimeoutException) reasonOf(e) else DriveClient.RENEW_MESSAGE)
            return false
        }
        return when (state) {
            is AuthState.Granted -> true
            is AuthState.NeedsUser -> {
                afterGoogle = resume
                ask(UiRequest.GoogleConsent(state.intent))
                false
            }
        }
    }

    /** Action sur les dossiers Drive : accord Google, puis [action] hors du fil principal, qui rend l'état dont les dossiers sont retenus. */
    private fun driveFolderAction(resume: () -> Unit, describe: (Throwable) -> String, action: (DriveClient) -> LocalState) {
        scope.launch {
            localRepository.awaitInitialized()
            if (!authorizeOrAsk(resume)) return@launch
            attempt { withContext(Dispatchers.IO) { action(driveClient()) } }
                .onSuccess { resolved ->
                    if (update { it.withFoldersOf(resolved) }.await().isFailure) return@launch
                    note("Dossier Drive choisi : ${resolved.knownFolders.firstOrNull { it.id == resolved.chosenFolderId }?.name ?: resolved.chosenFolderId}")
                    folderChanged()
                }
                .onFailure { e ->
                    val message = describe(e)
                    journal.error(Source.DRIVE, message)
                    say(message)
                }
        }
    }

    /** La liste Drive affichée était celle de l'ancien dossier : elle est retirée, puis relue. */
    private fun folderChanged() {
        driveFlow.value = null
        scope.launch { readDrive() }
    }

    private fun folderMessage(e: Throwable): String =
        if (e is DriveException && e.code == UNAUTHORIZED) DriveClient.RENEW_MESSAGE else "Dossier Drive inaccessible : ${reasonOf(e)}"

    /**
     * Sans adresse gardée, lit celle du compte dans Drive et la garde. Une adresse gardée n'est pas relue, et une adresse absente ne l'efface pas.
     * Appelée une fois par lecture de Drive, après la liste, et par [setDriveEnabled] tant qu'aucun dossier n'est choisi.
     * La lecture est lente : si l'envoi vers Drive a été désactivé entre-temps (par exemple par [disconnectGoogle]), l'adresse lue n'est pas gardée.
     */
    private suspend fun rememberAccount() {
        if (localFlow.driveAccount != null) return
        readAccount()?.let { email -> update { if (it.driveEnabled) it.copy(driveAccount = email) else it }.await() }
    }

    /**
     * Adresse du compte Google, lue dans Drive hors du fil principal, ou null. Un échec, ou une réponse sans adresse, donne une ligne
     * DRIVE d'erreur, une seule par lancement de l'app : cet accord est donné à chaque lecture de Drive.
     * Elle ne dit pas que l'accès ne se révoquera pas : une adresse gardée peut encore servir ([disconnectGoogle]).
     */
    private suspend fun readAccount(): String? {
        val result = attempt { withContext(Dispatchers.IO) { driveClient().accountEmail() } }
        val email = result.getOrNull()
        if (email == null && accountFailureNoted.compareAndSet(false, true)) {
            val reason = result.exceptionOrNull()?.let(::reasonOf) ?: "absente de la réponse de Drive"
            journal.error(Source.DRIVE, "adresse du compte Google : $reason")
        }
        return email
    }

    private fun LocalState.withFoldersOf(other: LocalState) = copy(knownFolders = other.knownFolders, chosenFolderId = other.chosenFolderId)

    private fun driveClient() = DriveClient(LoggingHttp(UrlHttp(), { journal.add(Source.DRIVE, it) }), auth.tokens())

    /** Un indicateur inconnu donne une ligne BLE par nom et par lancement de l'app : la liste est fusionnée à chaque changement. */
    private fun noteUnknownFlag(name: String, flag: Int) {
        if (!loggedFlags.add(name)) return
        val text = if (flag < 0) "absent" else "%02x inconnu".format(flag)
        journal.add(Source.BLE, "$name : indicateur $text, type Note retenu")
    }

    private fun buildUi(local: LocalState, phone: PhoneSnapshot, drive: DriveListing?, link: LinkStatus, live: Live): UiState {
        val listing = drive.takeIf { local.driveEnabled }
        val transcription = TranscriptionProgress.current(local.transcriptionTasks)
        val busy = Busy(live.recoveryName, live.recovery?.percent ?: 0, live.sendName, transcription?.first,
            transcription?.second?.percent?.takeIf { it != Progress.UNKNOWN })
        return UiState(
            local = local,
            recordings = Recordings.merge(local.boxList, phone.files, listing, local.errors, busy, ::noteUnknownFlag),
            link = if (local.deviceAddress == null) LinkStatus.NONE else link,
            refreshing = live.refreshing,
            recovering = live.recovering,
            sending = live.sending,
            recovery = live.recovery,
            send = live.send,
            driveSendRequest = live.driveSendRequest,
            deletionRequest = live.deletionRequest,
            deleting = live.deleting,
            notice = live.notice,
            transcription = transcription?.second,
            phoneFolder = local.phoneTreeUri?.let { runCatching { PhoneFolder.displayPath(it.toUri()) }.getOrDefault(it) },
            phoneFolderOk = phone.ok,
            phoneRevision = phone.revision,
            documentHashes = phone.hashesFor(local.phoneTreeUri),
            drive = listing,
            busy = busy,
            message = live.message,
        )
    }

    private companion object {
        const val UNAUTHORIZED = 401
    }
}
