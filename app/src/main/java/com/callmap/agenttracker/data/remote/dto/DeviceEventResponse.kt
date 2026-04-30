package com.callmap.agenttracker.data.remote.dto

data class DeviceEventResponse(
    val success: Boolean,
    val message: String,
    val data: DeviceEventData?
)

data class DeviceEventData(
    val id: Int,
    val event_type: String,
    val event_time: String
)
