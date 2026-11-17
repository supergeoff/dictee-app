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

/** Nombre d'enregistrements dont un VTT ou un MD du téléphone est absent de Drive. Zéro si Drive est désactivé ou sans liste. */
fun cloudSendCount(recordings: List<Recording>, drive: DriveListing?, driveEnabled: Boolean): Int {
    if (!driveEnabled || drive == null) return 0
    return recordings.count { recording ->
        Recordings.missingInDrive(recording, drive).any {
            RecordName.parse(it.fileName)?.kind.let { kind -> kind == FileKind.VTT || kind == FileKind.MD }
        }
    }
}
