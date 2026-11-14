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
    val transcription: Progress? = null,
    val phoneFolder: String? = null,      // chemin affiché, null sans dossier
    val phoneFolderOk: Boolean = false,
    val phoneRevision: Long = 0,
    val drive: DriveListing? = null,      // null si Drive est désactivé ou illisible
    val message: String? = null,
) {
    /** Enregistrements du boîtier à récupérer. */
    val toRecover: List<Recording>
        get() = Recordings.toRecover(recordings, drive)

    /** Enregistrements à envoyer : aucun si Drive est désactivé. */
    val toSend: List<Recording>
        get() = if (local.driveEnabled) Recordings.toSend(recordings, drive, local.errors) else emptyList()
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
internal data class PhoneSnapshot(val files: List<PhoneFile> = emptyList(), val ok: Boolean = false, val revision: Long = 0) {
    fun afterPromotion(): PhoneSnapshot = copy(revision = revision + 1)
}

/** Ce qui n'est pas dans `state.json` : travaux en cours avec leur avancement, et message à afficher. */
private data class Live(
    val refreshing: Boolean = false,
    val recovering: Boolean = false,
    val sending: Boolean = false,
    val recovery: Progress? = null,
    val recoveryName: String? = null,
    val send: Progress? = null,
    val sendName: String? = null,
    val message: String? = null,
)

private val BLUETOOTH_PERMISSIONS = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
private const val PHONE_FOLDER_LOST = "Dossier du téléphone inaccessible. Choisis-le de nouveau dans Réglages."
private const val OUT_OF_RANGE = "Boîtier hors de portée."
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

private fun reasonOf(e: Throwable): String = e.message ?: e.javaClass.simpleName

/**
 * Modèle de l'app : l'état local, la liste du téléphone, celle de Drive, la liaison et les travaux, réunis dans [ui].
 * Les écrans appellent ses actions. Ce qui demande le système (permission, dossier, accord Google) sort par [requests] :
 * l'action en attente est gardée, puis reprise par la méthode `on...` qui reçoit la réponse, ou abandonnée avec un message.
 */
class AppModel(
    private val app: Application,
    repository: LocalStateRepository? = null,
) {
    val journal = Journal(File(app.filesDir, "journal")) { LocalDateTime.now() }
    private val liveFlow = MutableStateFlow(Live())
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
    private val transcriptionCredentials = AndroidCredentialStore(app)
    val transcriptionSettings = TranscriptionSettingsManager(localRepository, transcriptionCredentials)
    private val synthesisCredentials = AndroidCredentialStore(app, "synthesis-credentials-v1", "synthesis-credentials")
    val synthesisSettings = SynthesisSettingsManager(localRepository, synthesisCredentials)
    private val synthesisClient = UrlSynthesisClient(synthesisCredentials)
    internal val transcriptionCoordinator: TranscriptionCoordinator by lazy {
        val scheduler = TranscriptionSchedule(androidx.work.WorkManager.getInstance(app))
        val client = UrlTranscriptionClient(transcriptionCredentials, { task ->
            PhoneFolder(app, task.folderUri.toUri()).open(task.mp3Name)
        })
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
            synthesisClient = synthesisClient,
            synthesisStorage = SynthesisStorage(File(app.filesDir, "synthesis")),
            phoneStore = { folder -> PhoneFolder(app, folder.toUri()) },
            synthesisCredentialAccessible = { ref -> runCatching { synthesisCredentials.read(ref).isNotBlank() }.getOrDefault(false) },
            schedule = scheduler::enqueue,
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
            launch = { startWork() })
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
    private val driveMutex = Mutex()
    private val loggedFlags = ConcurrentHashMap.newKeySet<String>()
    private val notificationAsked = AtomicBoolean(false)
    private val accountFailureNoted = AtomicBoolean(false)
    internal val jobs = ActiveWork()

    private val foregroundBoxGate = ForegroundBoxGate()
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
        scope.launch { transcriptionCoordinator.reconcile() }
        scope.launch { refreshWhenForeground(refreshCoordinator, { foregroundBoxGate.isForeground }, ::readForRefresh) }
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
        val saved = update { it.copy(deviceAddress = address, deviceName = name, boxList = emptyList()) }
        scope.launch {
            try {
                if (saved.await().isFailure) return@launch
                note("Boîtier associé : $name ($address)")
                box.close()
                if (hasBluetooth()) readBoxList()
            } finally {
                if (started) refreshCoordinator.finish()
            }
        }
    }

    // --- Récupération

    /** [names] nul : tout ce qui manque. */
    fun recover(names: List<String>?) {
        scope.launch {
            localRepository.awaitInitialized()
            recoverReady(names)
        }
    }

    private fun recoverReady(names: List<String>?) {
        if (liveFlow.value.recovering) return
        when {
            !hasBluetooth() -> askBluetooth { recover(names) }
            localFlow.phoneTreeUri == null -> askPhoneFolder { recover(names) }
            else -> scope.launch { recoverNow(names) }
        }
    }

    private suspend fun recoverNow(names: List<String>?) {
        val uri = localFlow.phoneTreeUri?.toUri() ?: return
        val address = localFlow.deviceAddress ?: return
        if (!withContext(Dispatchers.IO) { PhoneFolder.hasAccess(app, uri) }) {
            say(PHONE_FOLDER_LOST)
            return
        }
        val entries = recoveryEntries(names)
        if (entries.isEmpty() || liveFlow.getAndUpdate { it.copy(recovering = true) }.recovering) return
        jobs.begin()
        try {
            startWork()
            recoverySafety.withTransferLock { runRecovery(address, uri, entries) }
        } finally {
            jobs.end()
            liveFlow.update { it.copy(recovering = false, recovery = null, recoveryName = null) }
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
    private suspend fun runRecovery(address: String, uri: Uri, entries: List<ListItem.Entry>) {
        var connected = false
        val outcome = attempt {
            withContext(Dispatchers.IO) {
                box.withSession(address) { session ->
                    connected = true
                    RecoveryJob(session, PhoneFolder(app, uri), File(app.cacheDir, "recovery"), journal)
                        .run(entries, ::publishRecovery, ::applyRecovery) { entry -> recoverySafety.begin(entry.name) }
                }
            }
        }
        outcome.onSuccess { results -> if ((results.lastOrNull() as? RecoveryResult.Failed)?.stopsQueue == true) box.close() }
        outcome.onFailure { e ->
            if (e is RecoveryGuardException) {
                val message = reasonOf(e)
                say(message)
                journal.error(Source.FS, message)
            } else if (connected) markRecoveryFailed(e) else say(OUT_OF_RANGE)
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

    private suspend fun applyRecovery(result: RecoveryResult) {
        recoverySafety.finish(result)
        readPhone()
    }

    private suspend fun markRecoveryFailed(e: Throwable) {
        liveFlow.value.recoveryName?.let { name ->
            recoverySafety.finish(RecoveryResult.Failed(name, reasonOf(e), true))
        }
    }

    // --- Envoi vers Drive

    /**
     * [names] nul : tout ce qui manque dans Drive si sa liste est connue, sinon tout ce qui a un fichier sur le téléphone.
     * Sans envoi vers Drive activé, rien ne part.
     */
    fun send(names: List<String>?) {
        scope.launch {
            localRepository.awaitInitialized()
            if (localFlow.driveEnabled && !liveFlow.value.sending) sendNow(names)
        }
    }

    private val transcriptionLifecycle = InteractiveTranscriptionLifecycle(scope, ::followTranscriptions)

    /**
     * Lance la transcription de [names] (null : tout ce qui est éligible). La planification WorkManager est confirmée avant
     * la tâche durable et le service ; la boucle interactive suit ensuite les tâches jusqu'à leur fin ou leur suspension.
     */
    fun transcribe(names: Set<String>?, request: RequestedWork = RequestedWork.MISSING) {
        scope.launch {
            jobs.track {
                try {
                    if (request == RequestedWork.MISSING) announceExclusions(names)
                    val started = transcriptionCoordinator.start(names, request)
                    if (started.isNotEmpty()) transcriptionLifecycle.request().join()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    journal.error(Source.APP, "transcription : ${reasonOf(e)}")
                    say(reasonOf(e))
                }
            }
        }
    }

    /** Signale les éléments choisis qui ne seront pas transcrits : sans MP3 sur le téléphone ou en erreur de récupération. */
    private suspend fun announceExclusions(names: Set<String>?) {
        val state = localRepository.awaitInitialized()
        val current = buildUi(state, phoneFlow.value, driveFlow.value, box.status.value, liveFlow.value)
        val active = state.transcriptionTasks.filter { !it.phase.isTerminal() }.mapTo(mutableSetOf()) { it.recordingName }
        synthesisSelectionMessage(current.recordings, names, state.errors, active)?.let { say(it) }
    }

    private suspend fun followTranscriptions(): InteractiveRun {
        val scheduler = TranscriptionSchedule(androidx.work.WorkManager.getInstance(app))
        val runner = TranscriptionWorkerRunner(localRepository, transcriptionCoordinator, scheduler::cancel)
        return TranscriptionInteractiveRunner(localRepository, transcriptionCoordinator, runner).run()
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
                    say(reasonOf(e))
                }
            }
        }
    }

    /** Cesse le suivi interactif (timeout du service) ; le travail périodique déjà programmé prend le relais. */
    fun stopInteractiveTranscription() {
        transcriptionLifecycle.stop()
    }

    private suspend fun sendNow(names: List<String>?) {
        if (liveFlow.getAndUpdate { it.copy(sending = true) }.sending) return
        jobs.begin()
        try {
            if (!authorizeOrAsk { send(names) }) return
            recoverySafety.withTransferLock {
                val uri = localFlow.phoneTreeUri?.toUri() ?: return@withTransferLock
                val current = buildUi(localFlow, phoneFlow.value, driveFlow.value, box.status.value, liveFlow.value)
                val recordings = when {
                    names != null -> Recordings.sendable(current.recordings, current.local.errors).filter { it.name in names }
                    current.drive != null -> current.toSend
                    else -> Recordings.sendable(current.recordings, current.local.errors)
                }
                if (recordings.isEmpty()) return@withTransferLock
                startWork()
                withContext(Dispatchers.IO) { sendRecordings(uri, recordings, explicit = names != null) }
                readDrive()
            }
        } finally {
            jobs.end()
            liveFlow.update { it.copy(sending = false, send = null, sendName = null) }
        }
    }

    /** Hors du fil principal. Le message d'un envoi arrêté vient de [SendJob], qui l'a déjà noté au journal. */
    private suspend fun sendRecordings(uri: Uri, recordings: List<Recording>, explicit: Boolean) {
        val drive = driveClient()
        val folder = resolveSendFolder(drive) ?: return
        val failed = mutableSetOf<String>()   // un fichier envoyé ensuite ne doit pas effacer l'échec d'un autre du même enregistrement
        val outcome = SendJob(drive, PhoneFolder(app, uri), journal)
            .run(folder, recordings, ::publishSend, { applySend(it, failed) }, explicitSelection = explicit)
        if (outcome is SendOutcome.Stopped) say(outcome.reason)
    }

    private suspend fun resolveSendFolder(drive: DriveClient): DriveFolder? =
        try {
            val (folder, resolved) = resolveFolder(localFlow, drive)
            if (update { it.withFoldersOf(resolved) }.await().isFailure) null else folder
        } catch (e: Exception) {
            val message = folderMessage(e)
            journal.error(Source.DRIVE, message)
            say(message)
            null
        }

    private fun publishSend(name: String, progress: Progress) {
        val current = liveFlow.value
        if (current.sendName == name && current.send?.label() == progress.label()) return
        liveFlow.update { it.copy(send = progress, sendName = name) }
    }

    private fun applySend(result: SendResult, failed: MutableSet<String>) {
        if (result is SendResult.Failed) {
            failed += result.name
            update { it.withError(result.name, Step.SEND, result.reason) }
        } else if (result.name !in failed) {
            update { it.withoutError(result.name, Step.SEND) }
        }
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
            if (!keepPhoneFolder(uri)) return@launch
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

    val transcriptionForm = TranscriptionSettingsForm(scope) { settings, secret ->
        withContext(Dispatchers.IO) { transcriptionSettings.save(settings, secret) }
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
                phoneFlow.value.copy(ok = false)
            }
            if (localFlow.phoneTreeUri == folder) phoneFlow.value = snapshot.afterPromotion()
        }
    }

    /** Fichiers du dossier ; s'il ne répond pas, la dernière lecture marquée inaccessible, avec l'erreur au journal. */
    private fun readFolder(uri: Uri, last: PhoneSnapshot): PhoneSnapshot =
        try {
            if (!PhoneFolder.hasAccess(app, uri)) throw PhoneStoreException(PHONE_FOLDER_LOST)
            last.copy(files = PhoneFolder(app, uri).list(), ok = true)
        } catch (e: PhoneStoreException) {
            journal.error(Source.FS, e.journalText)
            last.copy(ok = false)
        }

    /**
     * La liste du boîtier remplace `boxList` ; un échec garde la dernière, et [BoxConnection] l'a déjà noté au journal.
     * Faux si le boîtier n'a pas répondu ; vrai si la liste est lue, ou si rien n'est à lire (aucun boîtier, récupération ou recherche en cours).
     */
    private suspend fun readBoxList(): Boolean = readBoxListForForeground(
        foregroundBoxGate, localRepository, { liveFlow.value.recovering || searching },
    ) { address -> attempt { box.list(address) } }

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
        attempt { withContext(Dispatchers.IO) { driveClient().listFiles(folderId) } }
            .onSuccess { files -> if (localFlow.chosenFolderId == folderId) driveFlow.value = DriveListing(files) }
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
            transcription = transcription?.second,
            phoneFolder = local.phoneTreeUri?.let { runCatching { PhoneFolder.displayPath(it.toUri()) }.getOrDefault(it) },
            phoneFolderOk = phone.ok,
            phoneRevision = phone.revision,
            drive = listing,
            message = live.message,
        )
    }

    private companion object {
        const val UNAUTHORIZED = 401
    }
}
