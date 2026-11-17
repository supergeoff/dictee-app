package top.supergeoff.dictee

fun unifiedDeletionTitle(plan: UnifiedDeletionPlan): String =
    if (plan.mode == DeletionMode.EVERYWHERE) "Supprimer partout ?" else "Libérer l'espace ?"

fun unifiedDeletionBody(plan: UnifiedDeletionPlan): List<String> {
    val lines = mutableListOf<String>()
    val count = plan.targets.size
    if (count > 0) {
        val levels = listOf(
            plan.targets.count { it.box } to "du boîtier",
            plan.targets.count { it.phone } to "du téléphone",
            plan.targets.count { it.driveFiles.isNotEmpty() } to "de Drive",
        ).filter { (n, _) -> n > 0 }.joinToString(", ") { (n, label) -> "$n $label" }
        val verb = if (count == 1) "enregistrement sera effacé" else "enregistrements seront effacés"
        lines += "$count $verb : $levels."
    }
    if (plan.mode == DeletionMode.FREE_SPACE) lines += "Les copies Drive sont conservées."
    if (plan.driveSkipped) lines += "Drive est désactivé : ses copies ne sont pas touchées."
    if (plan.blockedNames.isNotEmpty()) {
        lines += "Enregistrements occupés : ${plan.blockedNames.joinToString(", ")}. Attends la fin de leur traitement."
    }
    return lines
}

fun unifiedDeletionCanConfirm(plan: UnifiedDeletionPlan, executing: Boolean): Boolean =
    !executing && plan.targets.isNotEmpty() && plan.blockedNames.isEmpty()

fun unifiedDeletionFailureText(failures: List<String>): String? =
    failures.firstOrNull()?.let { "Suppression incomplète : $it (voir le journal)" }
