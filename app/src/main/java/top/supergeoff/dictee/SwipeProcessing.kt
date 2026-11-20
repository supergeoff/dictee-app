package top.supergeoff.dictee

/** Ce que fait un glissement vers la gauche sur la tuile d'un enregistrement. */
enum class SwipeProcessing { RECOVER, TRANSCRIBE, DONE, BUSY }

/**
 * Occupé : rien. MP3 absent du téléphone mais présent sur le boîtier : récupérer, puis le traitement automatique
 * enchaîne VTT et MD. VTT ou MD manquant : transcrire. Sinon, tout est déjà fait.
 */
fun swipeProcessing(r: Recording, busy: Boolean, hasVtt: Boolean, hasMd: Boolean): SwipeProcessing = when {
    busy -> SwipeProcessing.BUSY
    FileKind.MP3 !in r.phoneFiles && r.onBox -> SwipeProcessing.RECOVER
    !hasVtt || !hasMd -> SwipeProcessing.TRANSCRIBE
    else -> SwipeProcessing.DONE
}
