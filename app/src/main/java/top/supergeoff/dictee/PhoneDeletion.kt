package top.supergeoff.dictee

import java.util.concurrent.CancellationException

data class PhoneDeletionPlan(
    val folderUri: String,
    val recordings: List<Recording>,
    val blockedNames: Set<String>,
)

data class PhoneDeletionResult(val deleted: Set<String>, val failures: Map<String, String>)

object PhoneDeletion {
    fun plan(
        folderUri: String, recordings: List<Recording>, selected: Set<String>, protectedNames: Set<String>,
    ): PhoneDeletionPlan {
        val targets = recordings.filter { it.name in selected && it.phoneFiles.isNotEmpty() }.distinctBy { it.name }
        return PhoneDeletionPlan(folderUri, targets, targets.map { it.name }.toSet() intersect protectedNames)
    }

    fun execute(plan: PhoneDeletionPlan, store: PhoneStore): PhoneDeletionResult {
        val deleted = linkedSetOf<String>()
        val failures = linkedMapOf<String, String>()
        if (plan.blockedNames.isNotEmpty()) return PhoneDeletionResult(deleted, failures)
        val names = plan.recordings.flatMap { recording ->
            recording.phoneFiles.mapNotNull { (kind, file) ->
                val parsed = RecordName.parse(file.fileName)
                file.fileName.takeIf { parsed?.name == recording.name && parsed.kind == kind }
            }
        }.distinct()
        for (name in names) {
            try {
                store.delete(name)
                deleted += name
            } catch (e: CancellationException) {
                throw e
            } catch (e: PhoneStoreException) {
                failures[name] = e.message.orEmpty()
            }
        }
        return PhoneDeletionResult(deleted, failures)
    }
}
