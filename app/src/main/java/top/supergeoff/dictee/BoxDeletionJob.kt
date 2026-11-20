package top.supergeoff.dictee

import java.util.concurrent.CancellationException

data class BoxDeletionResult(
    val confirmed: Set<String>,
    val refused: Map<String, Int>,
    val unverified: Set<String>,
    val untreated: Set<String>,
    val error: String?,
)

class BoxDeletionJob(
    private val delete: suspend (String) -> Int,
    private val list: suspend () -> List<ListItem.Entry>,
    private val onListing: suspend (List<ListItem.Entry>) -> Unit,
) {
    suspend fun run(names: List<String>): BoxDeletionResult {
        val targets = names.distinct()
        val confirmed = linkedSetOf<String>()
        val refused = linkedMapOf<String, Int>()
        val unverified = linkedSetOf<String>()
        var error: String? = null
        var next = 0
        for ((index, name) in targets.withIndex()) {
            next = index + 1
            try {
                val status = delete(name)
                if (status != 0) refused[name] = status
                val listing = list()
                onListing(listing)
                if (status == 0) {
                    if (listing.any { it.name == name }) {
                        unverified += name
                        error = "suppression non vérifiée : $name est encore présent sur le boîtier"
                        break
                    }
                    confirmed += name
                }
            } catch (e: CancellationException) {
                if (name !in refused) unverified += name
                error = "Suppression interrompue ; état non vérifié."
                throw e
            } catch (e: Exception) {
                if (name !in refused) unverified += name
                error = e.message ?: e.javaClass.simpleName
                break
            }
        }
        return BoxDeletionResult(confirmed, refused, unverified, targets.drop(next).toSet(), error)
    }
}
