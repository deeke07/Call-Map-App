package com.callmap.agenttracker.data.remote.dto

import com.google.gson.annotations.SerializedName

data class DeviceRegistrationRequest(
    @SerializedName("passcode") val passcode: String,
    @SerializedName("user_email") val userEmail: String,
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("device_token") val deviceToken: String? = null,
    @SerializedName("platform") val platform: String = "android",
    @SerializedName("device_model") val deviceModel: String? = null,
    @SerializedName("app_version") val appVersion: String? = null,
    @SerializedName("push_token") val pushToken: String? = null,
    @SerializedName("battery_level") val batteryLevel: Int? = null
)

data class DeviceRegistrationResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String,
    @SerializedName("data") val data: DeviceRegistrationData?
)

data class DeviceRegistrationData(
    @SerializedName("id") val id: Int,
    @SerializedName("uuid") val uuid: String,
    @SerializedName("device_token") val deviceToken: String?,
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("platform") val platform: String,
    @SerializedName("device_model") val deviceModel: String?,
    @SerializedName("app_version") val appVersion: String?,
    @SerializedName("battery_level") val batteryLevel: String?,
    @SerializedName("user") val user: UserDto?,
    @SerializedName("settings") val settings: DeviceSettingsDto?
)

data class UserDto(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("email") val email: String,
    @SerializedName("profile") val profile: String,
)

data class DeviceSettingsDto(
    @SerializedName("device_id") val deviceId: Int,
    @SerializedName("recording_enabled") val recordingEnabled: Boolean,
    @SerializedName("location_enabled") val trackingEnabled: Boolean?,
    @SerializedName("remote_lock") val remoteLock: Boolean,
    @SerializedName("location_frequency") val locationFrequency: Long,
    @SerializedName("location_on_call") val locationOnCall: Boolean?,
    @SerializedName("location_high_accuracy") val locationHighAccuracy: Boolean?,
    @SerializedName("tracking_days") val trackingDays: List<String>?,
    @SerializedName("tracking_start_time") val trackingStartTime: String?,
    @SerializedName("tracking_end_time") val trackingEndTime: String?
)
