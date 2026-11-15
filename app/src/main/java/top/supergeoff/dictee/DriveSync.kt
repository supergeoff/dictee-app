package top.supergeoff.dictee

enum class DriveSendChoice { ALL_FILES, DOCUMENTS_ONLY }

data class DriveDestination(val account: String, val folderId: String)

data class DriveFile(val id: String, val name: String, val size: Long)

data class DriveReceipt(
    val destination: DriveDestination,
    val fileName: String,
    val driveFileId: String,
    val sha256: String,
)

enum class DriveWriteAction { SKIP, CREATE, UPDATE, CONFLICT }

object DriveSync {
    fun requiredKinds(choice: DriveSendChoice): Set<FileKind> = when (choice) {
        DriveSendChoice.ALL_FILES -> FileKind.entries.toSet()
        DriveSendChoice.DOCUMENTS_ONLY -> setOf(FileKind.VTT, FileKind.MD)
    }

    fun action(
        fileName: String,
        kind: FileKind,
        sha256: String?,
        destination: DriveDestination,
        remote: List<DriveFile>,
        receipts: List<DriveReceipt>,
    ): DriveWriteAction {
        val parsed = if (kind == FileKind.MP3) RecordName.parse(fileName) else null
        val legacyName = parsed?.let { "${it.name}.mp3" }
        val matching = remote.filter { it.name == fileName || (legacyName != null && it.name == legacyName) }
        if (matching.size > 1) return DriveWriteAction.CONFLICT
        val current = matching.singleOrNull() ?: return DriveWriteAction.CREATE
        if (kind == FileKind.MP3) return DriveWriteAction.SKIP

        val receiptIsCurrent = sha256 != null && receipts.any { receipt ->
            receipt.destination == destination &&
                receipt.fileName == fileName &&
                receipt.driveFileId == current.id &&
                receipt.sha256 == sha256
        }
        return if (receiptIsCurrent) DriveWriteAction.SKIP else DriveWriteAction.UPDATE
    }
}
