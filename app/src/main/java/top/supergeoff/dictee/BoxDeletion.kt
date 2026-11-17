package top.supergeoff.dictee

data class BoxDeletionPlan(
    val address: String,
    val names: List<String>,
    val blockedNames: Set<String>,
    val unbackedAudioCount: Int,
    val unbackedAudioNames: Set<String> = emptySet(),
)

object BoxDeletion {
    fun plan(
        address: String, recordings: List<Recording>, selected: Set<String>, protectedNames: Set<String>,
        drive: DriveListing?, local: LocalState,
    ): BoxDeletionPlan {
        val targets = recordings.filter { it.onBox && it.name in selected }.distinctBy { it.name }
        val destination = local.driveAccount?.let { account -> local.chosenFolderId?.let { DriveDestination(account, it) } }
        val unbacked = targets.filter { recording ->
            FileKind.MP3 !in recording.phoneFiles && (drive == null || destination == null || DriveSync.action(
                RecordName.fileName(recording.type, recording.name, FileKind.MP3), FileKind.MP3, null,
                destination, drive.entries, local.driveReceipts,
            ) != DriveWriteAction.SKIP)
        }
        val unbackedNames = unbacked.map { it.name }.toSet()
        val names = targets.map { it.name }
        return BoxDeletionPlan(address, names, names.toSet() intersect protectedNames, unbackedNames.size, unbackedNames)
    }
}
