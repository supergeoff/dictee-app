package top.supergeoff.dictee

/** Texte du toast après un renommage groupé : le MD est refait sur le nouveau VTT, ou la raison du refus. */
internal fun speakerRenameMessage(result: SpeakerRenameResult): String = when (result) {
    SpeakerRenameResult.Done -> "VTT mis à jour. Le MD est refait avec ces noms."
    is SpeakerRenameResult.Refused -> result.reason
}

/** Raison pour laquelle le nommage est indisponible, ou null s'il est disponible. */
internal fun speakerRenameUnavailableReason(taskActive: Boolean): String? =
    if (taskActive) "Un traitement est en cours sur cet enregistrement." else null

/**
 * État du dialogue « Locuteurs » : les [renames] valides et effectifs (étiquette vers nom normalisé), les étiquettes dont la saisie est
 * invalide ([invalidLabels]) et une phrase par groupe d'étiquettes qui finiront sous le même nom ([mergeNotices]).
 */
internal data class SpeakerRenameDraft(
    val renames: Map<String, String>,
    val invalidLabels: Set<String>,
    val mergeNotices: List<String>,
) {
    /** « Enregistrer » exige au moins un changement et aucune saisie invalide. */
    val canSave: Boolean get() = renames.isNotEmpty() && invalidLabels.isEmpty()
}

/**
 * Calcule l'état du dialogue à partir des étiquettes du VTT et de la saisie de chacune ([inputs]). Une saisie vide laisse l'étiquette ;
 * un nom égal à l'étiquette n'est pas un changement. Les noms se comparent à l'identique, comme dans le VTT.
 */
internal fun speakerRenameDraft(labels: List<String>, inputs: Map<String, String>): SpeakerRenameDraft {
    val invalid = linkedSetOf<String>()
    val renames = linkedMapOf<String, String>()
    val finalNames = linkedMapOf<String, String>()
    for (label in labels) {
        val input = inputs[label].orEmpty()
        val name = if (input.isBlank()) label else SpeakerNames.validate(input)
        if (name == null) {
            invalid += label
            continue
        }
        finalNames[label] = name
        if (name != label) renames[label] = name
    }
    val notices = finalNames.entries.groupBy({ it.value }, { it.key }).values
        .filter { it.size > 1 }
        .map { "${joinWithAnd(it)} seront fusionnés." }
    return SpeakerRenameDraft(renames, invalid, notices)
}

private fun joinWithAnd(items: List<String>): String =
    if (items.size == 1) items.single() else items.dropLast(1).joinToString(", ") + " et " + items.last()

/** Suggestions du carnet pour une saisie : filtrées sans casse ni accents, vides quand la saisie est déjà exactement un nom du carnet. */
internal fun speakerSuggestions(book: List<SpeakerName>, query: String): List<SpeakerName> =
    SpeakerNames.suggestions(book, query).filter { it.name != query.trim() }
