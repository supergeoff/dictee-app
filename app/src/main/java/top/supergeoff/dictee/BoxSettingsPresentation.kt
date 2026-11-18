package top.supergeoff.dictee

/**
 * Règles pures de la section Boîtier des réglages : ce qui rend les réglages éditables, la mention
 * d'état à afficher, les choix documentés de chaque réglage et la projection d'une valeur brute lue
 * vers son choix (ou vers aucun choix actif quand la valeur n'est pas documentée).
 */

/** Vrai seulement en ligne ([LinkStatus.CONNECTED]), avec une lecture fraîche du boîtier associé, hors de tout
 * travail et avec un état lu : seules ces conditions rendent les réglages du boîtier éditables. */
fun boxSettingsEditable(link: LinkStatus, fresh: Boolean, busy: Boolean, hasStatus: Boolean): Boolean =
    link == LinkStatus.CONNECTED && fresh && !busy && hasStatus

/** Mention sous les réglages : « Non lu » sans aucune lecture enregistrée, « Dernières valeurs lues » hors
 * ligne ou sans fraîcheur, rien pour une lecture fraîche de la liaison courante. */
fun boxSettingsStatusText(hasStatus: Boolean, fresh: Boolean): String? = when {
    !hasStatus -> "Non lu"
    !fresh -> "Dernières valeurs lues"
    else -> null
}

/** Les quatre choix de réduction du bruit, dans l'ordre du sélecteur (libellés de la conception DIC-9). */
val BOX_NOISE_CHOICES: List<Pair<NoiseReduction, String>> = listOf(
    NoiseReduction.DESACTIVE to "Coupée",
    NoiseReduction.FAIBLE to "Faible",
    NoiseReduction.MOYEN to "Moyenne",
    NoiseReduction.ELEVE to "Élevée",
)

/** Les quatre durées de segment, dans l'ordre du sélecteur. */
val BOX_DURATION_CHOICES: List<Pair<SegmentDuration, String>> =
    SegmentDuration.entries.map { it to it.label }

/** Les deux états du rétro-éclairage, dans l'ordre du sélecteur. */
val BOX_BACKLIGHT_CHOICES: List<Pair<Boolean, String>> = listOf(false to "Désactivé", true to "Activé")

/** Choix actif de réduction du bruit pour une valeur brute : null quand la valeur lue n'est pas documentée,
 * aucun choix n'est alors présenté comme actif. */
fun noiseReductionChoice(raw: Int): NoiseReduction? = NoiseReduction.entries.firstOrNull { it.raw == raw }

/** Choix actif de durée de segment pour une valeur brute : null quand la valeur lue n'est pas documentée. */
fun segmentDurationChoice(raw: Int): SegmentDuration? = SegmentDuration.entries.firstOrNull { it.raw == raw }

/** État du rétro-éclairage pour une valeur brute : null quand la valeur lue n'est pas documentée. */
fun backlightChoice(raw: Int): Boolean? = when (raw) {
    0x00 -> false
    0x01 -> true
    else -> null
}

/** Libellé de la batterie, en pourcentage lu sur le boîtier. */
internal fun batteryLabel(percent: Int): String = "$percent %"
