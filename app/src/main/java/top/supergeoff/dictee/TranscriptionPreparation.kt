package top.supergeoff.dictee

/** Fige le contexte de chaque enregistrement avant sa programmation durable. */
internal object TranscriptionPreparation {
    /** Valide uniquement les réglages nécessaires au plan ; l'orchestration MD reste distincte. */
    fun prepare(plan: FilePlan, settings: TranscriptionSettings): FilePlan {
        if (plan.makeVtt) {
            if (settings.baseUrl == "/v1" || !isValidTranscriptionBaseUrl(settings.baseUrl)) throw InvalidTranscriptionAddressException()
            settings.credentialRef?.takeIf { it.isNotBlank() } ?: throw CredentialException()
        }
        return plan
    }

    fun prepare(
        phone: PhoneStore,
        folderUri: String,
        settings: TranscriptionSettings,
        errors: Map<String, Map<Step, String>>,
        selectedNames: Set<String>?,
        newId: (String) -> String,
    ): List<TranscriptionTask> {
        val recordings = Recordings.merge(emptyList(), phone.list(), null, errors, Busy())
        val (plans, _) = SynthesisSelection.plan(recordings, selectedNames, errors, emptySet(), RequestedWork.MISSING)
        return plans.filter { it.makeVtt }.map { plan ->
            prepare(plan, settings)
            val recording = plan.recording
            val mp3 = checkNotNull(recording.phoneFiles[FileKind.MP3]).fileName
            val vtt = recording.phoneFiles[FileKind.VTT]?.fileName
                ?: RecordName.fileName(checkNotNull(RecordName.parse(mp3)).type, recording.name, FileKind.VTT)
            TranscriptionTask(
                localId = newId(recording.name), recordingName = recording.name, mp3Name = mp3, vttName = vtt,
                folderUri = folderUri, mp3Sha256 = TranscriptionAudio.sha256(phone, mp3),
                serverAddress = settings.baseUrl, encryptedKeyRef = checkNotNull(settings.credentialRef), language = settings.language,
                model = settings.model, phase = TranscriptionPhase.PENDING, replaceVtt = plan.replaceVtt,
            )
        }
    }
}
