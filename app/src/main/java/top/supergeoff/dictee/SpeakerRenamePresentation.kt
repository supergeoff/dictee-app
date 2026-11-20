package top.supergeoff.dictee

/** Texte du toast après un renommage groupé : le MD va être refait sur le nouveau VTT, ou la raison du refus. */
internal fun speakerRenameMessage(result: SpeakerRenameResult): String = when (result) {
    SpeakerRenameResult.Done -> "VTT mis à jour. Le MD va être refait avec ces noms."
    is SpeakerRenameResult.Refused -> result.reason
}

/** Raison pour laquelle le nommage est indisponible, ou null s'il est disponible. */
internal fun speakerRenameUnavailableReason(taskActive: Boolean): String? =
    if (taskActive) "Un traitement est en cours sur cet enregistrement." else null

/** Étiquette montrée à gauche de la ligne : l'origine du locuteur, ou son nom courant quand l'origine est inconnue. */
internal val SpeakerLabel.shownOrigin: String get() = original ?: current

/** Saisie de départ du champ : le nom courant s'il diffère de l'origine, sinon vide (le champ montre « (laisser A) »). */
internal val SpeakerLabel.initialInput: String get() = if (original != null && current != original) current else ""

/**
 * État du dialogue « Locuteurs » : les [renames] valides et effectifs (locuteur vers nom normalisé ou origine rétablie), les locuteurs dont la
 * saisie est invalide ([invalidLabels]) et une phrase par groupe de locuteurs qui finiront sous le même nom ([mergeNotices]).
 */
internal data class SpeakerRenameDraft(
    val renames: Map<SpeakerLabel, String>,
    val invalidLabels: Set<SpeakerLabel>,
    val mergeNotices: List<String>,
) {
    /** « Enregistrer » exige au moins un changement et aucune saisie invalide. */
    val canSave: Boolean get() = renames.isNotEmpty() && invalidLabels.isEmpty()
}

/**
 * Calcule l'état du dialogue à partir des locuteurs du VTT et de la saisie de chacun ([inputs], vide si absente). Une saisie vide vise
 * l'origine du locuteur, ou son nom courant quand l'origine est inconnue ; un nom égal au nom courant n'est pas un changement. Les noms
 * se comparent à l'identique, comme dans le VTT. Une fusion n'est annoncée que si elle change au moins un locuteur.
 */
internal fun speakerRenameDraft(labels: List<SpeakerLabel>, inputs: Map<SpeakerLabel, String>): SpeakerRenameDraft {
    val invalid = linkedSetOf<SpeakerLabel>()
    val renames = linkedMapOf<SpeakerLabel, String>()
    val finalNames = linkedMapOf<SpeakerLabel, String>()
    for (label in labels) {
        val input = inputs[label].orEmpty()
        val name = if (input.isBlank()) label.original ?: label.current else SpeakerNames.validate(input)
        if (name == null) {
            invalid += label
            continue
        }
        finalNames[label] = name
        if (name != label.current) renames[label] = name
    }
    val notices = finalNames.entries.groupBy({ it.value }, { it.key }).values
        .filter { group -> group.size > 1 && group.any { it in renames } }
        .map { group -> group.map { it.shownOrigin }.distinct() }
        .filter { it.size > 1 }
        .map { "${joinWithAnd(it)} seront fusionnés." }
    return SpeakerRenameDraft(renames, invalid, notices)
}

private fun joinWithAnd(items: List<String>): String =
    if (items.size == 1) items.single() else items.dropLast(1).joinToString(", ") + " et " + items.last()

/** Suggestions du carnet pour une saisie : filtrées sans casse ni accents, vides quand la saisie est déjà exactement un nom du carnet. */
internal fun speakerSuggestions(book: List<SpeakerName>, query: String): List<SpeakerName> =
    SpeakerNames.suggestions(book, query).filter { it.name != query.trim() }
