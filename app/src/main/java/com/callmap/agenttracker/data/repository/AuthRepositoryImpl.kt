package com.callmap.agenttracker.data.repository

import android.util.Log
import com.callmap.agenttracker.data.remote.api.AuthApi
import com.callmap.agenttracker.data.remote.dto.DeviceRegistrationRequest
import com.callmap.agenttracker.data.remote.dto.DeviceRegistrationResponse
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.domain.model.RegistrationResult
import com.callmap.agenttracker.domain.repository.AuthRepository
import com.callmap.agenttracker.util.Resource
import com.google.gson.Gson
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

class AuthRepositoryImpl @Inject constructor(
    private val api: AuthApi,
    private val sessionManager: SessionManager,
    private val gson: Gson
) : AuthRepository {

    override suspend fun registerDevice(request: DeviceRegistrationRequest): Resource<RegistrationResult> {
        return try {
            val response = api.registerDevice(request)
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true && body.data != null) {
                    val result = RegistrationResult(
                        deviceUuid = body.data.uuid,
                        deviceName = body.data.deviceName,
                        recordingEnabled = body.data.settings?.recordingEnabled ?: false,
                        trackingEnabled = body.data.settings?.trackingEnabled ?: false,
                        locationFrequency = (body.data.settings?.locationFrequency ?: 600) * 1000L,
                        agentEmail = body.data.user?.email ?: "",
                        agentName = body.data.user?.name ?: "",
                        agentProfile = body.data.user?.profile ?: "",
                        locationOnCall = body.data.settings?.locationOnCall ?: true,
                        locationHighAccuracy = body.data.settings?.locationHighAccuracy ?: false,
                        remoteLock = body.data.settings?.remoteLock ?: false,
                        trackingDays = body.data.settings?.trackingDays ?: emptyList(),
                        trackingStartTime = body.data.settings?.trackingStartTime,
                        trackingEndTime = body.data.settings?.trackingEndTime
                    )
                    Log.e("result","$result")
                    sessionManager.saveRegistration(result)
                    Resource.Success(result)
                } else {
                    Resource.Error(body?.message ?: "Registration failed")
                }
            } else {
                val errorBody = response.errorBody()?.string()
                val errorResponse = try {
                    gson.fromJson(errorBody, DeviceRegistrationResponse::class.java)
                } catch (e: Exception) {
                    null
                }
                Resource.Error(errorResponse?.message ?: "Error: ${response.code()}")
            }
        } catch (e: HttpException) {
            Resource.Error(e.localizedMessage ?: "An unexpected error occurred")
        } catch (e: IOException) {
            Resource.Error("Couldn't reach server. Check your internet connection.")
        }
    }
}
