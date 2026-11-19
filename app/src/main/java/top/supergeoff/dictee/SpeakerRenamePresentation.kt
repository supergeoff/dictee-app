package top.supergeoff.dictee

/** Texte du toast après un renommage de [label] ; [mdPresent] dit si le téléphone avait un MD à mettre à jour. */
internal fun speakerRenameMessage(result: SpeakerRenameResult, label: String, mdPresent: Boolean): String = when (result) {
    SpeakerRenameResult.Done -> "VTT et MD mis à jour."
    SpeakerRenameResult.VttOnly -> if (mdPresent) "VTT mis à jour. Le MD ne cite pas $label." else "VTT mis à jour."
    is SpeakerRenameResult.Refused -> result.reason
}

/** Raison pour laquelle le nommage est indisponible, ou null s'il est disponible. */
internal fun speakerRenameUnavailableReason(taskActive: Boolean): String? =
    if (taskActive) "Un traitement est en cours sur cet enregistrement." else null

/** Message de confirmation quand [name] est déjà une autre étiquette de [labels] ; null sinon. */
internal fun speakerMergeNotice(labels: List<String>, from: String, name: String): String? =
    if (name != from && name in labels) "$from deviendra aussi $name." else null

/** Puces du carnet filtrées par la saisie, et le nom à ajouter si la saisie est valide et absente des puces. */
internal data class SpeakerNameChoices(val suggestions: List<SpeakerName>, val addName: String?)

internal fun speakerNameChoices(book: List<SpeakerName>, query: String): SpeakerNameChoices {
    val suggestions = SpeakerNames.suggestions(book, query)
    val valid = SpeakerNames.validate(query)
    return SpeakerNameChoices(suggestions, valid?.takeIf { name -> suggestions.none { it.name == name } })
}
