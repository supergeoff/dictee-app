package top.supergeoff.dictee

internal fun recordingForDetail(recordings: List<Recording>, name: String): Recording? =
    recordings.firstOrNull { it.name == name }

internal fun selectionAfterPhoneDeletion(selected: Set<String>, targets: Set<String>, recordings: List<Recording>): Set<String> =
    recordings.filter { it.name in selected && (it.name !in targets || it.phoneFiles.isNotEmpty()) }
        .mapTo(HashSet()) { it.name }
