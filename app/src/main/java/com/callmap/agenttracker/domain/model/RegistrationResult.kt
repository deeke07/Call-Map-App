package com.callmap.agenttracker.domain.model

data class RegistrationResult(
    val deviceUuid: String,
    val deviceName: String,
    val recordingEnabled: Boolean,
    val trackingEnabled: Boolean,
    val locationFrequency: Long,
    val agentEmail: String?,
    val agentName: String?,
    val agentProfile: String?,
    val locationOnCall: Boolean = true,
    val locationHighAccuracy: Boolean = false,
    val remoteLock: Boolean = false,
    val lastSimId: String? = null,
    val trackingDays: List<String> = emptyList(),
    val trackingStartTime: String? = null,
    val trackingEndTime: String? = null,
    val monitorInternetStatus: Boolean = false,
    val deviceStatus: Boolean = true
)
