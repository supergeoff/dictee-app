package top.supergeoff.dictee

internal fun boxDeletionCountText(count: Int): String =
    "Supprimer $count enregistrement${if (count > 1) "s" else ""} du boîtier."

internal fun boxDeletionWarningText(count: Int): String? = if (count == 0) null else
    "Attention : aucune autre copie audio n'est confirmée pour $count enregistrement${if (count > 1) "s" else ""}."

internal fun boxDeletionCanConfirm(plan: BoxDeletionPlan, executing: Boolean): Boolean =
    !executing && plan.names.isNotEmpty() && plan.blockedNames.isEmpty()

internal fun boxDeletionResultText(result: BoxDeletionResult): String = listOfNotNull(
    "Confirmés : ${result.confirmed.sorted().joinToString().ifEmpty { "aucun" }}",
    "Refusés : ${result.refused.keys.sorted().joinToString().ifEmpty { "aucun" }}",
    "Non vérifiés : ${result.unverified.sorted().joinToString().ifEmpty { "aucun" }}",
    "Non traités : ${result.untreated.sorted().joinToString().ifEmpty { "aucun" }}",
    result.error,
).joinToString("\n")

internal fun selectionAfterBoxDeletion(selected: Set<String>, confirmed: Set<String>, recordings: List<Recording>): Set<String> =
    recordings.filter { it.name in selected && it.name !in confirmed }.mapTo(HashSet()) { it.name }
