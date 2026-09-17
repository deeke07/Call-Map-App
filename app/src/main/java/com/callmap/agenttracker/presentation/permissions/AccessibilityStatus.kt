package com.callmap.agenttracker.presentation.permissions

/** Permission selected in Settings and the system service connection are separate states. */
enum class AccessibilityStatus {
    CONNECTED, RECONNECTING, DISABLED, UNKNOWN;

    companion object {
        fun resolve(selected: Boolean?, connected: Boolean): AccessibilityStatus = when {
            selected == false -> DISABLED
            selected == null -> UNKNOWN
            connected -> CONNECTED
            else -> RECONNECTING
        }
    }
}
