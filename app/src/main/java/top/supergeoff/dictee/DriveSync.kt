package top.supergeoff.dictee

enum class DriveSendChoice { ALL_FILES, DOCUMENTS_ONLY }

data class DriveSendRequest(val names: List<String>?, val choice: DriveSendChoice)

/** Une demande ne peut être confirmée ou annulée qu'une seule fois. */
class DriveSendRequests {
    var current: DriveSendRequest? = null
        private set

    private var onConfirmed: (() -> Unit)? = null

    @Synchronized fun open(names: List<String>?, choice: DriveSendChoice, onConfirmed: (() -> Unit)? = null) {
        if (current == null && (names == null || names.isNotEmpty())) {
            current = DriveSendRequest(names?.toList(), choice)
            this.onConfirmed = onConfirmed
        }
    }
    @Synchronized fun cancel() { current = null; onConfirmed = null }
    @Synchronized fun confirm(choice: DriveSendChoice): DriveSendRequest? {
        val request = current?.copy(choice = choice) ?: return null
        current = null
        val callback = onConfirmed
        onConfirmed = null
        callback?.invoke()
        return request
    }
}

data class DriveDestination(val account: String, val folderId: String)

data class DriveFile(val id: String, val name: String, val size: Long)

data class DriveReceipt(
    val destination: DriveDestination,
    val fileName: String,
    val driveFileId: String,
    val sha256: String,
)

enum class DriveWriteAction { SKIP, CREATE, UPDATE, CONFLICT }

data class DriveSendSummary(val sendable: List<Recording>, val missingVtt: Int, val missingMd: Int)

object DriveSync {
    fun dialogSummary(
        recordings: List<Recording>, drive: DriveListing?, local: LocalState, hashes: Map<String, String>,
        request: DriveSendRequest, choice: DriveSendChoice,
    ): DriveSendSummary {
        val explicit = request.names?.let { names -> Recordings.sendable(recordings, local.errors).filter { it.name in names } }
        val selected = explicit ?: Recordings.toSendCurrent(recordings, drive, local.copy(driveSendChoice = choice), hashes)
        val warnings = explicit ?: Recordings.toSendCurrent(recordings, drive, local.copy(driveSendChoice = DriveSendChoice.ALL_FILES), hashes)
        return sendSummary(warnings, choice).copy(sendable = sendSummary(selected, choice).sendable)
    }

    fun confirmedSendable(
        recordings: List<Recording>, drive: DriveListing?, local: LocalState, hashes: Map<String, String>,
        request: DriveSendRequest, choice: DriveSendChoice, displayedNames: Set<String>,
    ): List<Recording> = dialogSummary(recordings, drive, local, hashes, request, choice)
        .sendable.filter { it.name in displayedNames }

    fun sendSummary(recordings: List<Recording>, choice: DriveSendChoice) = DriveSendSummary(
        recordings.filter { recording -> recording.phoneFiles.keys.any { it in requiredKinds(choice) } },
        recordings.count { FileKind.VTT !in it.phoneFiles },
        recordings.count { FileKind.MD !in it.phoneFiles },
    )

    fun isComplete(
        recording: Recording, choice: DriveSendChoice, destination: DriveDestination,
        remote: List<DriveFile>, hashes: Map<String, String>, receipts: List<DriveReceipt>,
    ): Boolean {
        val documentsComplete = listOf(FileKind.VTT, FileKind.MD).all { kind ->
            val file = recording.phoneFiles[kind]
            if (file != null) {
                val hash = hashes[file.fileName] ?: return@all false
                action(file.fileName, kind, hash, destination, remote, receipts) == DriveWriteAction.SKIP
            } else {
                hasConfirmedCopy(recording.name, kind, destination, remote, receipts)
            }
        }
        if (!documentsComplete) return false
        if (choice == DriveSendChoice.DOCUMENTS_ONLY) return true
        val mp3Name = RecordName.fileName(recording.type, recording.name, FileKind.MP3)
        return action(mp3Name, FileKind.MP3, null, destination, remote, receipts) == DriveWriteAction.SKIP
    }

    fun hasConfirmedCopy(
        recordingName: String, kind: FileKind, destination: DriveDestination,
        remote: List<DriveFile>, receipts: List<DriveReceipt>,
    ): Boolean {
        val current = remote.filter { entry ->
            RecordName.parse(entry.name)?.let { it.name == recordingName && it.kind == kind } == true ||
                (kind == FileKind.MP3 && RecordName.legacyMp3(entry.name) == recordingName)
        }.singleOrNull() ?: return false
        return receipts.any { receipt ->
            receipt.destination == destination && receipt.fileName == current.name &&
                receipt.driveFileId == current.id && receipt.sha256.isNotBlank()
        }
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
