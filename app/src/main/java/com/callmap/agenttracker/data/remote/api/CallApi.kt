package com.callmap.agenttracker.data.remote.api

import com.callmap.agenttracker.data.remote.dto.DeviceEventRequest
import com.callmap.agenttracker.data.remote.dto.DeviceEventResponse
import com.google.gson.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface CallApi {

    @Headers("Accept: application/json")
    @POST("public/device-sims/bulk")
    suspend fun registerDeviceSims(
        @Body request: com.callmap.agenttracker.data.remote.dto.DeviceSimBulkRequest
    ): Response<com.callmap.agenttracker.data.remote.dto.DeviceSimBulkResponse>

    @Headers("Accept: application/json")
    @Multipart
    @POST("public/calls")
    suspend fun submitCallLog(
        @Part("device_uuid") deviceUuid: RequestBody,
        @Part("device_sim_uuid") deviceSimUuid: RequestBody?,
        @Part("sim_slot") simSlot: RequestBody?,
        @Part("carrier_name") carrierName: RequestBody?,
        @Part("client_number") clientNumber: RequestBody,
        @Part("call_type") callType: RequestBody,
        @Part("call_duration") callDuration: RequestBody,
        @Part("call_started_at") callStartedAt: RequestBody,
        @Part("call_ended_at") callEndedAt: RequestBody,
        @Part("call_answered_at") callAnsweredAt: RequestBody?,
        @Part("caller_name") callerName: RequestBody,
        @Part("duration_mismatch") durationMismatch: RequestBody,
        @Part("was_on_hold") wasOnHold: RequestBody,
        @Part("interrupted_numbers") interruptedNumbers: RequestBody?,
        @Part("spot_setting_version") spotSettingVersion: RequestBody?,
        @Part("apk_version") apkVersion: RequestBody?,
        @Part("latitude") latitude: RequestBody?,
        @Part("longitude") longitude: RequestBody?,
        @Part("battery_level") batteryLevel: RequestBody?,
        @Part("meta_data") metaData: RequestBody?,
        @Part call_recording_file: MultipartBody.Part?
    ): Response<JsonObject>

    @Headers("Accept: application/json")
    @POST("public/device-event-logs")
    suspend fun submitDeviceEvent(
        @Body request: DeviceEventRequest
    ): Response<DeviceEventResponse>
}
