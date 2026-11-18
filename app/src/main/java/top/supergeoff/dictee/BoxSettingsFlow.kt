package top.supergeoff.dictee

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** Lecture du boîtier (commande 0x05) rattachée à l'adresse de la liaison qui l'a produite. */
data class BoxReading(val address: String, val status: BoxStatus)

/**
 * Enregistre [reading] comme dernière lecture du boîtier, rattachée à l'adresse encore associée :
 * une lecture reçue pour une ancienne adresse est ignorée, sans écriture. Propage l'échec d'une sauvegarde.
 */
suspend fun recordBoxReading(repository: LocalStateRepository, reading: BoxReading): LocalState {
    return repository.updateDurably {
        if (it.deviceAddress == reading.address) it.copy(boxStatus = reading.status) else it
    }
}

/**
 * Vrai seulement si une lecture de la liaison courante correspond au boîtier associé et que la
 * liaison est ouverte : hors ligne ou après un changement de boîtier, les valeurs affichées sont
 * les dernières lues, non une lecture fraîche.
 */
fun isBoxStatusFresh(associatedAddress: String?, readingAddress: String?, link: LinkStatus): Boolean =
    associatedAddress != null && readingAddress == associatedAddress && link == LinkStatus.CONNECTED

internal data class BoxUiPublication(val local: LocalState, val fresh: Boolean, val message: String?)

/** La valeur fraîche et son éventuel message proviennent de la même publication durable. */
internal fun boxUiPublication(
    local: LocalState, link: LinkStatus, publication: BoxReadingPublication?,
    current: BoxReadingPublication?, message: String?, messagePublication: BoxReadingPublication?,
): BoxUiPublication {
    val reading = publication?.takeIf { it === current && isBoxStatusFresh(local.deviceAddress, it.reading.address, link) }
    return BoxUiPublication(
        local = if (reading != null) local.copy(boxStatus = reading.reading.status) else local,
        fresh = reading != null,
        message = when {
            message == null || messagePublication == null || messagePublication === reading -> message
            messagePublication === current && isBoxStatusFresh(local.deviceAddress, current.reading.address, link) -> null
            else -> BOX_SETTING_UNVERIFIED
        },
    )
}

/** Une seule écriture de réglage à la fois : [tryStart] est faux tant qu'une écriture est admise. */
class BoxSettingAdmission {
    private val active = AtomicBoolean(false)

    fun tryStart(): Boolean = active.compareAndSet(false, true)

    fun finish() {
        active.set(false)
    }
}

/** Message après l'écriture d'un réglage, à partir de l'acquittement et de la relecture. */
fun boxSettingMessage(setting: BoxSetting, result: BoxSettingResult): String = when {
    !result.accepted -> "Le boîtier a refusé ce réglage."
    result.isApplied(setting) -> "Réglage appliqué et relu."
    else -> "Réglage non appliqué. La valeur relue est affichée."
}

private const val BOX_SETTING_UNVERIFIED = "Réglage non vérifié. Reconnecte le boîtier avant de réessayer."

/**
 * Écrit [setting] par [send] et affiche le message de la relecture ([boxSettingMessage]) :
 * succès concordant, refus ou divergence, la valeur réelle étant publiée par la liaison et
 * enregistrée par le collecteur des lectures du boîtier. Une exception de session garde
 * l'ancienne valeur affichée, signale un réglage non vérifié et ne conserve aucune
 * intention de rejeu : la commande peut avoir été appliquée, elle n'est jamais renvoyée.
 */
internal suspend fun deliverBoxSetting(
    setting: BoxSetting,
    send: suspend (BoxSetting) -> BoxSettingResult,
    onMessage: (String) -> Unit,
) {
    val message = try {
        boxSettingMessage(setting, send(setting))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        BOX_SETTING_UNVERIFIED
    }
    onMessage(message)
}
