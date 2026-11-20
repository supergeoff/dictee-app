package top.supergeoff.dictee

internal fun requestBoxDeletionWithPermission(hasBluetooth: Boolean, askPermission: () -> Unit, request: () -> Unit) {
    if (hasBluetooth) request() else askPermission()
}
