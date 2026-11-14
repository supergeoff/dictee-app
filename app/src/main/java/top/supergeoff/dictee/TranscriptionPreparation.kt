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
        phone: PhoneStore, folderUri: String, settings: TranscriptionSettings,
        errors: Map<String, Map<Step, String>>, selectedNames: Set<String>?,
        synthesisSettings: SynthesisSettings, request: RequestedWork, activeNames: Set<String> = emptySet(), newId: (String) -> String,
    ): List<TranscriptionTask> {
        val recordings = Recordings.merge(emptyList(), phone.list(), null, errors, Busy())
        val (plans, _) = SynthesisSelection.plan(recordings, selectedNames, errors, activeNames, request)
        return plans.map { plan ->
            prepare(plan, settings)
            val recording = plan.recording
            val mp3 = checkNotNull(recording.phoneFiles[FileKind.MP3]).fileName
            val type = checkNotNull(RecordName.parse(mp3)).type
            val vtt = recording.phoneFiles[FileKind.VTT]?.fileName ?: RecordName.fileName(type, recording.name, FileKind.VTT)
            val md = recording.phoneFiles[FileKind.MD]?.fileName ?: RecordName.fileName(type, recording.name, FileKind.MD)
            val configured = synthesisSettings.baseUrl != "/v1" && isValidSynthesisBaseUrl(synthesisSettings.baseUrl) &&
                synthesisSettings.model.isNotBlank() && synthesisSettings.prompt.isNotBlank() && !synthesisSettings.credentialRef.isNullOrBlank()
            val metadata = PromptMetadata(recording.type.label,
                RecordName.dateTime(recording.name).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm", java.util.Locale.FRENCH)),
                if (recording.size > 0) RecordName.formatDuration(RecordName.durationSeconds(recording.size)) else "Non disponible")
            val context = if (!configured || !plan.makeMd) null else SynthesisContext(synthesisSettings.baseUrl,
                checkNotNull(synthesisSettings.credentialRef), synthesisSettings.model, synthesisSettings.prompt, metadata, null, null)
            val ready = if (!plan.makeVtt && context != null) {
                val bytes = phone.open(vtt).use { it.readBytes() }
                val values = SynthesisPrompt.variables(recording, bytes)
                context.copy(systemPrompt = SynthesisPrompt.render(context.promptTemplate, values), vttSha256 = TranscriptionAudio.sha256(bytes))
            } else context
            val reason = if (context == null) "Configure la synthèse puis crée explicitement un travail MD." else null
            val work = if (!plan.makeMd) null else SynthesisWork(
                if (plan.makeVtt) SynthesisPhase.WAITING_VTT else if (ready != null) SynthesisPhase.READY else SynthesisPhase.SUSPENDED,
                md, plan.replaceMd, ready, 0, null, null, reason)
            TranscriptionTask(newId(recording.name), recording.name, mp3, vtt, folderUri,
                if (plan.makeVtt) TranscriptionAudio.sha256(phone, mp3) else "",
                if (plan.makeVtt) settings.baseUrl else "", if (plan.makeVtt) checkNotNull(settings.credentialRef) else "",
                if (plan.makeVtt) settings.language else "", if (plan.makeVtt) settings.model else "",
                if (plan.makeVtt) TranscriptionPhase.PENDING else if (ready == null) TranscriptionPhase.COMPLETED else TranscriptionPhase.SYNTHESIZING,
                synthesis = work, replaceVtt = plan.replaceVtt)
        }
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
