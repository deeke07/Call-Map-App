package com.callmap.agenttracker.data.remote.dto

import com.google.gson.annotations.SerializedName

data class DeviceSimBulkRequest(
    @SerializedName("device_uuid") val deviceUuid: String,
    @SerializedName("sims") val sims: List<SimRequestDto>
)

data class SimRequestDto(
    @SerializedName("sim_slot") val simSlot: Int,
    @SerializedName("carrier_name") val carrierName: String,
    @SerializedName("phone_number") val phoneNumber: String?,
    @SerializedName("iccid") val iccid: String?,
    @SerializedName("mcc") val mcc: String?,
    @SerializedName("mnc") val mnc: String?,
    @SerializedName("country_iso") val countryIso: String?,
    @SerializedName("is_active") val isActive: Boolean
)

data class DeviceSimBulkResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String,
    @SerializedName("data") val data: List<SimResponseDto>
)

data class SimResponseDto(
    @SerializedName("id") val id: Int,
    @SerializedName("uuid") val uuid: String,
    @SerializedName("device_id") val deviceId: Int,
    @SerializedName("sim_slot") val simSlot: Int,
    @SerializedName("carrier_name") val carrierName: String,
    @SerializedName("phone_number") val phoneNumber: String?,
    @SerializedName("iccid") val iccid: String?,
    @SerializedName("mcc") val mcc: String?,
    @SerializedName("mnc") val mnc: String?,
    @SerializedName("country_iso") val countryIso: String?,
    @SerializedName("is_active") val isActive: Boolean,
    @SerializedName("last_seen") val lastSeen: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String
)
