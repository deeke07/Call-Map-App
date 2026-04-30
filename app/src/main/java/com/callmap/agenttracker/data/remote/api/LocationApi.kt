package com.callmap.agenttracker.data.remote.api

import com.callmap.agenttracker.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface LocationApi {

    @POST("public/device-locations")
    @Headers("Accept: application/json")
    suspend fun submitLocation(
        @Body request: LocationRequest
    ): Response<LocationResponse>

    @POST("public/device-locations/bulk")
    @Headers("Accept: application/json")
    suspend fun submitBulkLocations(
        @Body request: BulkLocationRequest
    ): Response<BulkLocationResponse>
}
