package com.callmap.agenttracker.data.remote.dto

data class DeviceEventRequest(
    val device_uuid: String,
    val event_type: String,
    val event_time: String,
    val permission_name: String? = null,
    val metadata: Map<String, Any>? = null
)
