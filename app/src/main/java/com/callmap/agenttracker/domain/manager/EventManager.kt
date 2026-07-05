package com.callmap.agenttracker.domain.manager

interface EventManager {
    fun logEvent(
        eventType: String,
        permissionName: String? = null,
        metadata: Map<String, Any>? = null
    )

    companion object {
        // Official Server Whitelist (Backend updated to include PERMISSION_ENABLED)
        const val PERMISSION_ENABLED = "PERMISSION_ENABLED"
        const val PERMISSION_DISABLED = "PERMISSION_DISABLED"
        const val RECORDING_OFF_DURING_CALL = "RECORDING_OFF_DURING_CALL"
        const val DEVICE_ONLINE = "DEVICE_ONLINE"
        const val DEVICE_OFFLINE = "DEVICE_OFFLINE"
        const val DEVICE_RESTARTED = "DEVICE_RESTARTED"
        const val LOCATION_DISABLED = "LOCATION_DISABLED"
        const val LOCATION_ENABLED = "LOCATION_ENABLED"
        const val MICROPHONE_PERMISSION_DENIED = "MICROPHONE_PERMISSION_DENIED"
        const val BACKGROUND_ACTIVITY_RESTRICTED = "BACKGROUND_ACTIVITY_RESTRICTED"
        const val BATTERY_OPTIMIZATION_ENABLED = "BATTERY_OPTIMIZATION_ENABLED"
        const val LOCATION_TRACKING_STOPPED = "LOCATION_TRACKING_STOPPED"

    }
}
