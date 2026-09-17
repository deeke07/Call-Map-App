package com.callmap.agenttracker.util

import org.junit.Assert.*
import org.junit.Test

class CallIdentityTest {
    @Test fun retryAndReconciliationUseTheSameStableId() {
        assertEquals(CallIdentity.fromSystemLog("device", 12, 1000), CallIdentity.fromSystemLog("device", 12, 1000))
    }
    @Test fun newCallsAndOtherDevicesNeverReuseTheSameId() {
        val first = CallIdentity.fromSystemLog("device", 12, 1000)
        assertNotEquals(first, CallIdentity.fromSystemLog("device", 13, 1000))
        assertNotEquals(first, CallIdentity.fromSystemLog("other", 12, 1000))
        assertNotEquals(first, CallIdentity.fromSystemLog("device", 12, 2000))
    }
}
