package com.callmap.agenttracker.data.remote.dto

import com.google.gson.annotations.SerializedName

data class LocationRequest(
    @SerializedName("device_uuid") val deviceUuid: String,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("battery_level") val batteryLevel: Int?,
    @SerializedName("recorded_at") val recordedAt: String?
)

data class BulkLocationRequest(
    @SerializedName("device_uuid") val deviceUuid: String,
    @SerializedName("locations") val locations: List<LocationItem>
)

data class LocationItem(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("battery_level") val batteryLevel: Int?,
    @SerializedName("recorded_at") val recordedAt: String?
)

data class LocationResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String,
    @SerializedName("data") val data: LocationResponseData
)

data class LocationResponseData(
    @SerializedName("settings") val settings: LocationSettings?
)

data class BulkLocationResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String,
    @SerializedName("data") val data: BulkLocationResponseData
)

data class BulkLocationResponseData(
    @SerializedName("inserted") val inserted: Int,
    @SerializedName("settings") val settings: LocationSettings?
)

data class LocationSettings(
    @SerializedName("location_enabled") val locationEnabled: Boolean,
    @SerializedName("location_frequency") val locationFrequency: Int, // in seconds
    @SerializedName("location_high_accuracy") val locationHighAccuracy: Boolean
)
