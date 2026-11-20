package top.supergeoff.dictee

fun unifiedDeletionTitle(plan: UnifiedDeletionPlan): String =
    if (plan.mode == DeletionMode.EVERYWHERE) "Supprimer partout ?" else "Libérer l'espace ?"

fun unifiedDeletionBody(plan: UnifiedDeletionPlan): List<String> {
    val lines = mutableListOf<String>()
    val count = plan.targets.size
    if (count == 1) plan.labels[plan.targets.single().name]?.let { lines += "Enregistrement : $it." }
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
    if (plan.driveUnreadable) lines += "Drive illisible : ses copies ne seront pas touchées."
    if (plan.phoneUnreadable) lines += "Dossier du téléphone illisible : ses fichiers ne seront pas touchés."
    if (plan.blockedNames.isNotEmpty()) {
        lines += "Enregistrements occupés : ${plan.blockedNames.joinToString(", ")}. Attends la fin de leur traitement."
    }
    return lines
}

fun unifiedDeletionCanConfirm(plan: UnifiedDeletionPlan, executing: Boolean): Boolean =
    !executing && plan.targets.isNotEmpty() && plan.blockedNames.isEmpty()

fun unifiedDeletionFailureText(failures: List<String>): String? =
    failures.firstOrNull()?.let { "Suppression incomplète : $it (voir le journal)" }

/** Texte du snackbar après exécution : un échec prime sur la réduction du plan (niveaux retirés, voir [UnifiedDeletion.restrict]). */
fun unifiedDeletionNotice(failures: List<String>, dropped: List<String>): String? =
    unifiedDeletionFailureText(failures)
        ?: dropped.takeIf { it.isNotEmpty() }?.let { "Suppression réduite : l'état a changé depuis la confirmation (voir le journal)" }
