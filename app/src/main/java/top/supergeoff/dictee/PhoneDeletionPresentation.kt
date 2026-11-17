package top.supergeoff.dictee

internal fun recordingForDetail(recordings: List<Recording>, name: String): Recording? =
    recordings.firstOrNull { it.name == name }
