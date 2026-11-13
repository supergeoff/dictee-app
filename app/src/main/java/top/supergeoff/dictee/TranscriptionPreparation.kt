package top.supergeoff.dictee

/** Fige le contexte de chaque enregistrement avant sa programmation durable. */
internal object TranscriptionPreparation {
    fun prepare(
        phone: PhoneStore,
        folderUri: String,
        settings: TranscriptionSettings,
        errors: Map<String, Map<Step, String>>,
        selectedNames: Set<String>?,
        newId: (String) -> String,
    ): List<TranscriptionTask> {
        if (settings.baseUrl == "/v1" || !isValidTranscriptionBaseUrl(settings.baseUrl)) throw InvalidTranscriptionAddressException()
        val ref = settings.credentialRef?.takeIf { it.isNotBlank() } ?: throw CredentialException()
        val recordings = Recordings.merge(emptyList(), phone.list(), null, errors, Busy())
        return Recordings.toTranscribe(recordings, selectedNames, errors).candidates.map { recording ->
            val mp3 = checkNotNull(recording.phoneFiles[FileKind.MP3]).fileName
            val vtt = recording.phoneFiles[FileKind.VTT]?.fileName
                ?: RecordName.fileName(checkNotNull(RecordName.parse(mp3)).type, recording.name, FileKind.VTT)
            TranscriptionTask(
                localId = newId(recording.name), recordingName = recording.name, mp3Name = mp3, vttName = vtt,
                folderUri = folderUri, mp3Sha256 = TranscriptionAudio.sha256(phone, mp3),
                serverAddress = settings.baseUrl, encryptedKeyRef = ref, language = settings.language,
                model = settings.model, phase = TranscriptionPhase.PENDING,
            )
        }
    }
}
