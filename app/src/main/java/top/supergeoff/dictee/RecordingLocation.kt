package top.supergeoff.dictee

/** Filtre de la liste par emplacement : un enregistrement présent à plusieurs endroits figure dans plusieurs filtres. */
enum class LocationFilter(val label: String) {
    ALL("Tout"),
    BOX("Boîtier"),
    PHONE("Téléphone"),
    DRIVE("Drive");

    fun matches(r: Recording, drive: DriveListing?): Boolean = when (this) {
        ALL -> true
        BOX -> r.onBox
        PHONE -> r.phoneFiles.isNotEmpty()
        DRIVE -> driveFilesOf(r.name, drive).isNotEmpty()
    }
}

/** Nombre d'enregistrements admissibles dans les formats Drive activés. */
fun cloudSendCount(recordings: List<Recording>, drive: DriveListing?, local: LocalState, hashes: Map<String, String>): Int {
    if (!local.driveEnabled) return 0
    return Recordings.toSendCurrent(recordings, drive, local, hashes).size
}
