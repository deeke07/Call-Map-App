package com.callmap.agenttracker.domain.usecase

import android.util.Log
import com.callmap.agenttracker.data.remote.api.AuthApi
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.domain.manager.SyncManager
import com.callmap.agenttracker.domain.model.RegistrationResult
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class FetchConfigUseCase @Inject constructor(
    private val authApi: AuthApi,
    private val sessionManager: SessionManager,
    private val syncManager: SyncManager,
    private val logoutUseCase: LogoutUseCase
) {
    suspend operator fun invoke() {
        Log.i("FetchConfigUseCase", "Fetching configuration...")
        try {
            val currentRegistration = sessionManager.getRegistration().first()
            if (currentRegistration == null) {
                Log.w("FetchConfigUseCase", "Aborted: No registration found in SessionManager")
                return
            }
            
            val response = authApi.getDeviceConfig(mapOf("device_uuid" to currentRegistration.deviceUuid))
            
            if (response.isSuccessful) {
                val data = response.body()?.data
                if (data != null) {
                    val settings = data.settings
                    val updatedRegistration = currentRegistration.copy(
                        recordingEnabled = settings?.recordingEnabled ?: currentRegistration.recordingEnabled,
                        trackingEnabled = settings?.trackingEnabled ?: currentRegistration.trackingEnabled,
                        locationFrequency = (settings?.locationFrequency ?: currentRegistration.locationFrequency) * 1000L,
                        locationOnCall = settings?.locationOnCall ?: currentRegistration.locationOnCall,
                        locationHighAccuracy = settings?.locationHighAccuracy ?: currentRegistration.locationHighAccuracy,
                        remoteLock = settings?.remoteLock ?: currentRegistration.remoteLock,
                        trackingDays = settings?.trackingDays ?: currentRegistration.trackingDays,
                        trackingStartTime = settings?.trackingStartTime ?: currentRegistration.trackingStartTime,
                        trackingEndTime = settings?.trackingEndTime ?: currentRegistration.trackingEndTime,
                        monitorInternetStatus = settings?.monitorInternetStatus ?: currentRegistration.monitorInternetStatus,
                        deviceStatus = settings?.deviceStatus ?: currentRegistration.deviceStatus
                    )

                    // Critical: Check if device is disabled
                    if (!updatedRegistration.deviceStatus) {
                        Log.w("FetchConfigUseCase", "Device status is FALSE. Triggering emergency logout.")
                        logoutUseCase()
                        return
                    }

                    sessionManager.saveRegistration(updatedRegistration)
                    Log.i("FetchConfigUseCase", "Config updated: $updatedRegistration")
                    syncManager.scheduleTrackingAudit()
                }
            } else {
                Log.e("FetchConfigUseCase", "Failed to fetch config: ${response.errorBody()?.string()}")
            }
        } catch (e: Exception) {
            Log.e("FetchConfigUseCase", "Error fetching config", e)
        }
    }
}
