package top.supergeoff.dictee

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.app.Notification as AndroidNotification

/**
 * Service de premier plan qui garde la récupération et l'envoi en vie, écran éteint.
 * Sa notification reprend l'avancement de [AppModel.ui]. Il s'arrête dès que [AppModel.activeJobs] tombe à zéro, lu à chaque
 * démarrage et à chaque changement de [AppModel.ui] : un travail déjà fini quand le service démarre l'arrête aussitôt.
 * Un verrou partiel d'une heure au plus garde le processeur éveillé.
 */
class WorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStartId = 0
    private var stopping = false
    private var shownText = ""

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Travaux en cours", NotificationManager.IMPORTANCE_LOW))
        enterForeground(PREPARING)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dictee:work")
            .apply { acquire(WAKE_LOCK_MS) }
        scope.launch { model().ui.collect { onUi(it) } }
    }

    /**
     * Un nouveau démarrage reçu pendant l'arrêt l'annule : [stopSelf] avec l'identifiant précédent est alors ignoré.
     * Le service repasse au premier plan, puis s'arrête de nouveau si plus aucun travail ne tourne.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        stopping = false
        enterForeground(shownText.ifEmpty { PREPARING })
        onUi(model().ui.value)
        return START_NOT_STICKY
    }

    /** Android 15 limite la durée d'un service `dataSync` et exige l'arrêt dans les secondes qui suivent ce rappel. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        model().stopInteractiveTranscription()
        stopping = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun model(): AppModel = (application as DicteeApp).model

    private fun onUi(ui: UiState) {
        if (stopping) return
        if (model().activeJobs == 0) {
            stopping = true
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(lastStartId)
            return
        }
        show(listOfNotNull(ui.recovery?.label(), ui.send?.label(), ui.transcription?.label()).joinToString("\n").ifEmpty { PREPARING })
    }

    private fun show(text: String) {
        if (text == shownText) return
        shownText = text
        notifications.notify(NOTIFICATION_ID, build(text))
    }

    private fun enterForeground(text: String) {
        shownText = text
        startForeground(NOTIFICATION_ID, build(text), foregroundType())
    }

    /**
     * Le type `connectedDevice` exige une permission Bluetooth accordée dès Android 14 : sans elle, par exemple pour un envoi
     * seul, le service ne déclare que `dataSync`, qui est contenu dans les types du manifeste.
     */
    private fun foregroundType(): Int {
        val bluetooth = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val connected = if (bluetooth) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or connected
    }

    private fun build(text: String): AndroidNotification {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return AndroidNotification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(AndroidNotification.BigTextStyle().bigText(text))
            .setContentIntent(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(AndroidNotification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL = "work"
        private const val NOTIFICATION_ID = 1
        private const val PREPARING = "Préparation…"
        private const val WAKE_LOCK_MS = 60 * 60 * 1000L

        /** Démarre le service. Un démarrage que le système refuse est noté au journal, et le travail continue sans service. */
        fun start(context: Context) {
            val app = context.applicationContext
            try {
                app.startForegroundService(Intent(app, WorkService::class.java))
            } catch (e: ForegroundServiceStartNotAllowedException) {
                (app as DicteeApp).model.journal.error(Source.APP, "service de premier plan refusé : ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
