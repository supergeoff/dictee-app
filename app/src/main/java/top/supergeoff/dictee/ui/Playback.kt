package top.supergeoff.dictee.ui

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.supergeoff.dictee.AppModel
import top.supergeoff.dictee.Source
import java.io.FileNotFoundException
import java.util.Locale

/** Intervalle de relecture de la position pendant la lecture. */
private const val POLL_MS = 250L

/** Le lecteur n'accepte la lecture qu'une fois [READY] ; après [FAILED] il ne repart pas. */
internal enum class PlaybackStatus { PREPARING, READY, FAILED }

/**
 * Lecture d'un MP3 par un `MediaPlayer`, qui vit et meurt avec l'écran de la fiche. Le lecteur se pilote depuis le fil principal,
 * mais le fichier est ouvert ailleurs : [open] reçoit un descripteur déjà ouvert, car l'ouverture d'un dossier synchronisé dans
 * le nuage peut bloquer. Il le referme dès que le lecteur en a pris une copie, puis prépare sans bloquer. Une erreur d'ouverture,
 * de source ou de décodage passe en [PlaybackStatus.FAILED] au lieu de planter, avec la cause technique dans [failure] pour le
 * journal.
 */
@Stable
internal class Playback {
    var status by mutableStateOf(PlaybackStatus.PREPARING)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set

    /** Cause technique de l'échec, pour le journal ; null tant que tout va bien. */
    var failure: String? by mutableStateOf(null)
        private set

    /** Avancement entre 0 et 1, nul tant que la durée est inconnue. */
    val fraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    private var player: MediaPlayer? = null
    private var released = false

    /**
     * Prépare la lecture du fichier ouvert dans [source]. Le descripteur est refermé ici dans tous les cas, y compris si l'écran est
     * déjà fermé ; un fichier introuvable ou refusé par le système passe en échec.
     */
    fun open(source: Result<AssetFileDescriptor>) {
        source.onSuccess { descriptor -> descriptor.use { if (!released) prepare(it) } }
        source.onFailure { if (!released) fail(it.message ?: it.javaClass.simpleName) }
    }

    private fun prepare(descriptor: AssetFileDescriptor) {
        val next = MediaPlayer()
        player = next
        try {
            next.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            next.setOnPreparedListener { onPrepared(it) }
            next.setOnCompletionListener { onCompleted() }
            next.setOnErrorListener { _, what, extra ->
                fail("erreur du lecteur $what ($extra)")
                true
            }
            next.setDataSource(descriptor)
            next.prepareAsync()
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Lance la lecture, ou la met en pause si elle tourne. Sans effet tant que le lecteur n'est pas prêt. */
    fun toggle() {
        val current = player ?: return
        if (status != PlaybackStatus.READY) return
        try {
            if (current.isPlaying) current.pause() else current.start()
            playing = current.isPlaying
        } catch (e: IllegalStateException) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    fun pause() {
        val current = player ?: return
        if (!playing) return
        try {
            current.pause()
            playing = false
            positionMs = current.currentPosition
        } catch (e: IllegalStateException) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Se place à la [fraction] de la durée, entre 0 et 1. */
    fun seekTo(fraction: Float) {
        val current = player ?: return
        if (status != PlaybackStatus.READY) return
        val target = (fraction.coerceIn(0f, 1f) * durationMs).toInt()
        try {
            current.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
            positionMs = target
        } catch (e: IllegalStateException) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Relit la position du lecteur, à appeler pendant la lecture seulement. */
    fun refresh() {
        val current = player ?: return
        try {
            positionMs = current.currentPosition
        } catch (e: IllegalStateException) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Libère le lecteur, une seule fois : à la sortie de l'écran. */
    fun release() {
        released = true
        playing = false
        player?.release()
        player = null
    }

    private fun onPrepared(prepared: MediaPlayer) {
        if (prepared !== player) return
        durationMs = prepared.duration.coerceAtLeast(0)
        status = PlaybackStatus.READY
    }

    private fun onCompleted() {
        playing = false
        positionMs = 0
    }

    private fun fail(reason: String) {
        player?.release()
        player = null
        playing = false
        status = PlaybackStatus.FAILED
        failure = reason
    }
}

/**
 * Crée le [Playback] du MP3 [fileName] et le libère en quittant l'écran ; il est refait si le fichier change de taille, par exemple
 * après une nouvelle récupération. L'adresse du fichier se cherche et le fichier s'ouvre hors du fil principal. La lecture se met en
 * pause quand l'activité s'arrête, la position n'est relue que pendant la lecture, et un échec est noté au journal sans jamais
 * en faire planter l'écran, même si le journal est illisible.
 */
@Composable
internal fun rememberPlayback(model: AppModel, fileName: String, size: Long): Playback {
    val appContext = LocalContext.current.applicationContext
    val playback = remember(fileName, size) { Playback() }
    LaunchedEffect(playback) {
        // Toute l'étape est NonCancellable : si l'écran se ferme pendant l'ouverture, un withContext annulé abandonnerait le descripteur
        // déjà ouvert. Ici il arrive toujours à open, qui le referme.
        withContext(NonCancellable) {
            playback.open(withContext(Dispatchers.IO) {
                val uri = model.phoneUri(fileName)
                openDescriptor(appContext) { uri }
            })
        }
    }
    DisposableEffect(playback) { onDispose { playback.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playback.pause() }
    LaunchedEffect(playback, playback.playing) {
        while (playback.playing) {
            playback.refresh()
            delay(POLL_MS)
        }
    }
    LaunchedEffect(playback.failure) {
        val reason = playback.failure ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { model.journal.error(Source.APP, "lecture de $fileName : $reason") }
    }
    return playback
}

/** Ouvre en lecture le fichier que [find] désigne ; à appeler hors du fil principal, un dossier dans le nuage pouvant bloquer. */
private fun openDescriptor(context: Context, find: () -> Uri?): Result<AssetFileDescriptor> = try {
    val uri = find() ?: throw FileNotFoundException("fichier introuvable dans le dossier du téléphone")
    val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
        ?: throw FileNotFoundException("fichier refusé par le système")
    Result.success(descriptor)
} catch (e: Exception) {
    Result.failure(e)
}

/** Durée ou position affichée, par exemple « 12:04 », ou « 1:02:03 » au-delà d'une heure. */
internal fun formatClock(millis: Int): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val seconds = total % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}
