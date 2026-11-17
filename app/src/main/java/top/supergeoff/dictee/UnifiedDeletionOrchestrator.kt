package top.supergeoff.dictee

import kotlin.coroutines.cancellation.CancellationException

/** [deleted] : fichiers Drive mis à la corbeille, fichiers du téléphone effacés et enregistrements retirés du boîtier. */
data class UnifiedDeletionOutcome(val deleted: Set<String>, val failures: List<String>)

/**
 * Exécute un plan confirmé : Drive, puis téléphone, puis boîtier. L'échec d'un niveau n'arrête pas les suivants.
 * Un plan bloqué n'efface rien. Le verrou de transfert et la garde de suppression sont pris par l'appelant.
 * [deletePhone] rend null quand le niveau n'a pas pu s'exécuter ; [deleteBox] lève quand le boîtier est hors de portée.
 */
class UnifiedDeletionOrchestrator(
    private val trashDrive: suspend (DriveFile) -> Unit,
    private val deletePhone: suspend (names: Set<String>) -> PhoneDeletionResult?,
    private val deleteBox: suspend (names: List<String>) -> BoxDeletionResult,
    private val onDriveTrashed: suspend (Set<String>) -> Unit,
) {
    suspend fun execute(plan: UnifiedDeletionPlan): UnifiedDeletionOutcome {
        val deleted = linkedSetOf<String>()
        val failures = mutableListOf<String>()
        if (plan.blockedNames.isNotEmpty()) return UnifiedDeletionOutcome(deleted, failures)

        val trashedIds = linkedSetOf<String>()
        for (file in plan.targets.flatMap { it.driveFiles }.distinctBy { it.id }) {
            try {
                trashDrive(file)
                trashedIds += file.id
                deleted += file.name
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "Drive : ${file.name} : ${reasonText(e)}"
            }
        }
        if (trashedIds.isNotEmpty()) {
            try {
                onDriveTrashed(trashedIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "Drive : reçus non mis à jour : ${reasonText(e)}"
            }
        }

        val phoneNames = plan.targets.filter { it.phone }.map { it.name }.toSet()
        if (phoneNames.isNotEmpty()) {
            try {
                val result = deletePhone(phoneNames)
                if (result == null) failures += "Téléphone : suppression annulée" else {
                    deleted += result.deleted
                    result.failures.forEach { (file, reason) -> failures += "Téléphone : $file : $reason" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "Téléphone : ${reasonText(e)}"
            }
        }

        val boxNames = plan.targets.filter { it.box }.map { it.name }
        if (boxNames.isNotEmpty()) {
            try {
                val result = deleteBox(boxNames)
                deleted += result.confirmed
                result.refused.forEach { (name, status) -> failures += "Boîtier : $name : refusé (code $status)" }
                result.error?.let { failures += "Boîtier : $it" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "Boîtier : ${reasonText(e)}"
            }
        }
        return UnifiedDeletionOutcome(deleted, failures)
    }

    private fun reasonText(e: Exception): String = e.message ?: e.javaClass.simpleName
}
