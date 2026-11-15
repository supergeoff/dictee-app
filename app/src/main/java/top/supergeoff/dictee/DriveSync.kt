package top.supergeoff.dictee

enum class DriveSendChoice { ALL_FILES, DOCUMENTS_ONLY }

data class DriveSendRequest(val names: List<String>?, val choice: DriveSendChoice)

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
    fun isComplete(
        recording: Recording, choice: DriveSendChoice, destination: DriveDestination,
        remote: List<DriveFile>, hashes: Map<String, String>, receipts: List<DriveReceipt>,
    ): Boolean {
        val documentsComplete = listOf(FileKind.VTT, FileKind.MD).all { kind ->
            val file = recording.phoneFiles[kind] ?: return@all false
            val hash = hashes[file.fileName] ?: return@all false
            action(file.fileName, kind, hash, destination, remote, receipts) == DriveWriteAction.SKIP
        }
        if (!documentsComplete) return false
        if (choice == DriveSendChoice.DOCUMENTS_ONLY) return true
        val mp3Name = RecordName.fileName(recording.type, recording.name, FileKind.MP3)
        return action(mp3Name, FileKind.MP3, null, destination, remote, receipts) == DriveWriteAction.SKIP
    }

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
        val parsed = if (kind == FileKind.MP3) RecordName.parse(fileName)?.takeIf { it.kind == FileKind.MP3 } else null
        val names = if (parsed != null) {
            setOf("${parsed.name}.mp3") + RecordType.entries.map { RecordName.fileName(it, parsed.name, FileKind.MP3) }
        } else setOf(fileName)
        val matching = remote.filter { it.name in names }
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
