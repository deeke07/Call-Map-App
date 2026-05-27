package com.callmap.agenttracker.data.remote.api

import com.callmap.agenttracker.data.remote.dto.DeviceRegistrationRequest
import com.callmap.agenttracker.data.remote.dto.DeviceRegistrationResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface AuthApi {

    @POST("public/devices/register")
    @Headers("Accept: application/json")
    suspend fun registerDevice(
        @Body request: DeviceRegistrationRequest
    ): Response<DeviceRegistrationResponse>

    @POST("public/device")
    @Headers("Accept: application/json")
    suspend fun getDeviceConfig(
        @Body request: Map<String, String>
    ): Response<DeviceRegistrationResponse>

    @POST("public/device/offline")
    @Headers("Accept: application/json")
    suspend fun markDeviceOffline(
        @Body request: Map<String, String>
    ): Response<Map<String, Any>>

    companion object {
        const val BASE_URL = "https://callmap.solz.cloud/api/"
    }
}
