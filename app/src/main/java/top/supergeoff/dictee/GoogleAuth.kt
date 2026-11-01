package top.supergeoff.dictee

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Attente maximale d'une réponse de Google Play Services. */
private const val TIMEOUT_SECONDS = 30L

/** Message d'une [TimeoutException] de Google Play Services : il va au journal et à l'écran tel quel. */
private const val TIMEOUT_MESSAGE = "Google ne répond pas, réessaie plus tard"

/** Accès à Drive : accord donné (le jeton vient de [GoogleAuth.tokens], à chaque requête), ou écran d'accord de Google à parcourir. */
sealed class AuthState {
    data object Granted : AuthState()

    data class NeedsUser(val intent: PendingIntent) : AuthState()
}

/** Accès à Google Drive par Google Play Services : jeton, écran d'accord, sélecteur de dossier et révocation. */
class GoogleAuth(context: Context) {
    private val client = Identity.getAuthorizationClient(context)

    /**
     * Vérifie sans interaction que l'accord Drive est donné. Une erreur de Google lève une exception, une réponse trop lente une [TimeoutException],
     * et un accord sans jeton une [DriveException] 401 : l'utilisateur doit renouveler l'accès.
     */
    suspend fun authorize(): AuthState {
        val result = client.authorize(DriveClient.authorizationRequest()).await()
        if (result.hasResolution()) return AuthState.NeedsUser(result.pendingIntent!!)
        if (result.accessToken == null) throw DriveException(401, DriveClient.RENEW_MESSAGE)
        return AuthState.Granted
    }

    /** Lit le retour de l'écran d'accord. Faux si Google n'a pas donné de jeton. */
    fun granted(data: Intent?): Boolean =
        try {
            client.getAuthorizationResultFromIntent(data).accessToken != null
        } catch (e: Exception) {
            false
        }

    /**
     * Source de jetons pour [DriveClient]. À appeler hors du fil principal : chaque demande attend la réponse de Google,
     * au plus 30 secondes, puis lève une [TimeoutException].
     * Un jeton qui demande l'écran d'accord lève une [DriveException] 401 : l'utilisateur doit renouveler l'accès.
     */
    fun tokens(): TokenSource = object : TokenSource {
        override fun token(): String {
            val result = awaitBlocking(client.authorize(DriveClient.authorizationRequest()))
            if (result.hasResolution()) throw DriveException(401, DriveClient.RENEW_MESSAGE)
            return result.accessToken ?: throw DriveException(401, DriveClient.RENEW_MESSAGE)
        }

        override fun invalidate(token: String) {
            try {
                awaitBlocking(client.clearToken(ClearTokenRequest.builder().setToken(token).build()))
            } catch (e: Exception) {
                // Un jeton qui reste dans le cache de Google n'empêche rien : le prochain 401 refera la demande.
            }
        }
    }

    /** Écran du sélecteur de dossier Drive, ou null si Google n'a besoin d'aucune interaction. Une erreur ou un délai de Google lève une exception. */
    suspend fun picker(): PendingIntent? {
        val result = client.authorize(DriveClient.folderPickerRequest()).await()
        return if (result.hasResolution()) result.pendingIntent else null
    }

    /** Identifiant du dossier choisi, lu dans `picked_file_ids` du retour du sélecteur. Renvoie null si aucun dossier n'est reçu. */
    fun picked(data: Intent?): String? =
        try {
            client.getAuthorizationResultFromIntent(data).tokenResponseParams
                ?.getString("picked_file_ids")
                ?.split(",")
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }

    /** Retire à l'app le droit `drive.file` du compte [account]. */
    suspend fun revoke(account: String) {
        val request = RevokeAccessRequest.builder()
            .setAccount(Account(account, "com.google"))
            .setScopes(listOf(Scope(DriveClient.SCOPE)))
            .build()
        client.revokeAccess(request).await()
    }
}

/** Attend la fin de la tâche Google Play Services sans bloquer le fil, au plus 30 secondes : au-delà, une [TimeoutException]. */
private suspend fun <T> Task<T>.await(): T =
    try {
        withTimeout(TIMEOUT_SECONDS * 1_000) {
            suspendCancellableCoroutine { cont ->
                addOnSuccessListener { cont.resume(it) }
                addOnFailureListener { cont.resumeWithException(it) }
                addOnCanceledListener { cont.cancel() }
            }
        }
    } catch (e: TimeoutCancellationException) {
        throw TimeoutException(TIMEOUT_MESSAGE)   // une annulation du délai n'est pas celle de l'appelant : elle doit sortir comme une erreur
    }

/** Attend la fin de la tâche en bloquant le fil, au plus 30 secondes : à appeler hors du fil principal. */
private fun <T> awaitBlocking(task: Task<T>): T =
    try {
        Tasks.await(task, TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (e: TimeoutException) {
        throw TimeoutException(TIMEOUT_MESSAGE)
    }
