package top.supergeoff.dictee

enum class DriveSendChoice { ALL_FILES, DOCUMENTS_ONLY }

data class DriveSendRequest(val names: List<String>?, val formats: Set<FileKind>, val customizeFormats: Boolean = false)

/** Une demande ne peut être confirmée ou annulée qu'une seule fois. */
class DriveSendRequests {
    var current: DriveSendRequest? = null
        private set

    private var onConfirmed: (() -> Unit)? = null

    @Synchronized fun open(names: List<String>?, formats: Set<FileKind>, customizeFormats: Boolean = false, onConfirmed: (() -> Unit)? = null) {
        if (current == null && (names == null || names.isNotEmpty())) {
            current = DriveSendRequest(names?.toList(), formats.toSet(), customizeFormats)
            this.onConfirmed = onConfirmed
        }
    }
    @Synchronized fun cancel() { current = null; onConfirmed = null }
    @Synchronized fun confirm(formats: Set<FileKind>): DriveSendRequest? {
        val request = current?.copy(formats = formats.toSet()) ?: return null
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
        request: DriveSendRequest, formats: Set<FileKind>,
    ): DriveSendSummary {
        val eligible = Recordings.toSendCurrent(recordings, drive, local.copy(driveSendFormats = formats), hashes)
        val selected = request.names?.let { names -> eligible.filter { it.name in names } } ?: eligible
        val warningCandidates = Recordings.toSendCurrent(recordings, drive, local.copy(driveSendFormats = FileKind.entries.toSet()), hashes)
        val warnings = request.names?.let { names -> warningCandidates.filter { it.name in names } } ?: warningCandidates
        return sendSummary(warnings, formats).copy(sendable = selected)
    }

    fun confirmedSendable(
        recordings: List<Recording>, drive: DriveListing?, local: LocalState, hashes: Map<String, String>,
        request: DriveSendRequest, formats: Set<FileKind>, displayedNames: Set<String>,
    ): List<Recording> = dialogSummary(recordings, drive, local, hashes, request, formats)
        .sendable.filter { it.name in displayedNames }

    fun sendSummary(recordings: List<Recording>, formats: Set<FileKind>) = DriveSendSummary(
        recordings.filter { recording -> recording.phoneFiles.keys.any { it in formats } },
        if (FileKind.VTT in formats) recordings.count { FileKind.VTT !in it.phoneFiles } else 0,
        if (FileKind.MD in formats) recordings.count { FileKind.MD !in it.phoneFiles } else 0,
    )

    fun isComplete(
        recording: Recording, formats: Set<FileKind>, destination: DriveDestination,
        remote: List<DriveFile>, hashes: Map<String, String>, receipts: List<DriveReceipt>,
    ): Boolean = formats.isNotEmpty() && formats.all { kind ->
        if (kind == FileKind.MP3) {
            val name = RecordName.fileName(recording.type, recording.name, kind)
            action(name, kind, null, destination, remote, receipts) == DriveWriteAction.SKIP
        } else {
            val file = recording.phoneFiles[kind]
            if (file == null) hasConfirmedCopy(recording.name, kind, destination, remote, receipts)
            else hashes[file.fileName]?.let { action(file.fileName, kind, it, destination, remote, receipts) == DriveWriteAction.SKIP } ?: false
        }
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
