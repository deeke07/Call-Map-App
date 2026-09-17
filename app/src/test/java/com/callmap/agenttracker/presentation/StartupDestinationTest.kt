package com.callmap.agenttracker.presentation

import com.callmap.agenttracker.presentation.permissions.AccessibilityStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupDestinationTest {
    @Test fun registeredUserCanReachHomeBeforeAccessibilityReconnects() {
        assertEquals("home", startupDestination(true, true, true, null))
        assertEquals(AccessibilityStatus.RECONNECTING, AccessibilityStatus.resolve(true, false))
    }

    @Test fun revokedAccessibilityDoesNotSendRegisteredUserToSetup() {
        assertEquals("home", startupDestination(true, true, false, "permissions"))
        assertEquals("home", startupDestination(true, true, false, null))
        assertEquals(AccessibilityStatus.DISABLED, AccessibilityStatus.resolve(false, true))
    }

    @Test fun unreadableSettingIsNotReportedAsRevoked() {
        assertEquals(AccessibilityStatus.UNKNOWN, AccessibilityStatus.resolve(null, false))
    }

    @Test fun existingCorePermissionRequirementsStillApply() {
        assertEquals("permissions", startupDestination(true, false, true, "home"))
        assertEquals("welcome", startupDestination(false, false, false, null))
        assertEquals("register", startupDestination(false, true, true, "permissions"))
    }

    @Test fun reconnectFinishesWithoutReregistering() {
        assertEquals(AccessibilityStatus.CONNECTED, AccessibilityStatus.resolve(true, true))
        assertEquals("home", startupDestination(true, true, true, "permissions"))
    }
}
