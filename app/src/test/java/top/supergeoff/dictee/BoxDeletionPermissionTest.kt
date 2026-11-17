package top.supergeoff.dictee

import org.junit.Assert.assertEquals
import org.junit.Test

class BoxDeletionPermissionTest {
    @Test fun missingBluetoothPermissionDefersDeletionRequestUntilGranted() {
        var requests = 0
        var permissionRequests = 0
        requestBoxDeletionWithPermission(false, { permissionRequests++ }, { requests++ })
        assertEquals(0, requests)
        assertEquals(1, permissionRequests)
        requestBoxDeletionWithPermission(true, { permissionRequests++ }, { requests++ })
        assertEquals(1, requests)
        assertEquals(1, permissionRequests)
    }
}
