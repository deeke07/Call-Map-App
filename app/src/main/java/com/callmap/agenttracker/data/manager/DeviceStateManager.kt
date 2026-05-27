package com.callmap.agenttracker.data.manager

import android.content.Context
import android.util.Log
import com.callmap.agenttracker.domain.manager.EventManager
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.domain.repository.DeviceEventRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceStateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    val sessionManager: SessionManager,
    private val eventRepository: DeviceEventRepository,
    private val eventManager: EventManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Tracks a binary state (Enabled/Disabled) and logs transitions.
     */
    suspend fun trackBinaryState(
        stateKey: String,
        isEnabled: Boolean,
        enabledEvent: String?,
        disabledEvent: String?,
        permissionName: String? = null 
    ) = withContext(Dispatchers.IO) {
        try {
            val registration = sessionManager.getRegistration().first()
            val monitorInternetStatus = registration?.monitorInternetStatus ?: false

            // Filter network status events based on monitor_internet_status flag
            val skipEvent = (enabledEvent == EventManager.DEVICE_ONLINE || disabledEvent == EventManager.DEVICE_OFFLINE) && !monitorInternetStatus

            val storageKey = if (permissionName != null) "${stateKey}_$permissionName" else stateKey
            val states = sessionManager.getDeviceStates().first()
            val currentVal = states[storageKey] ?: "false"
            val currentState = currentVal.toBoolean()
            
            if (currentState != isEnabled) {
                sessionManager.updateDeviceState(storageKey, isEnabled.toString())
                
                if (!skipEvent) {
                    val eventToLog = if (isEnabled) enabledEvent else disabledEvent
                    eventToLog?.let {
                        Log.i("DeviceStateManager", "Transition: $storageKey -> $isEnabled")
                        // Use EventManager to handle the specific metadata structure required by the backend
                        eventManager.logEvent(
                            eventType = it,
                            permissionName = permissionName
                        )
                    }
                } else {
                    Log.d("DeviceStateManager", "Skipping event $enabledEvent/$disabledEvent because monitor_internet_status is false")
                }
            }
        } catch (e: Exception) {
            Log.e("DeviceStateManager", "Error tracking state for $stateKey", e)
        }
    }

    /**
     * Tracks a string value (e.g., SIM ID) and logs if it changes.
     */
    suspend fun trackValueState(
        stateKey: String,
        currentValue: String,
        eventType: String,
        metadataKey: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            val states = sessionManager.getDeviceStates().first()
            val previousValue = states[stateKey] ?: ""
            
            if (previousValue != currentValue && currentValue.isNotEmpty()) {
                sessionManager.updateDeviceState(stateKey, currentValue)
                Log.i("DeviceStateManager", "Value Change: $stateKey ($previousValue -> $currentValue)")
                eventManager.logEvent(
                    eventType = eventType,
                    metadata = if (metadataKey != null) mapOf(metadataKey to currentValue) else null
                )
            }
        } catch (e: Exception) {
            Log.e("DeviceStateManager", "Error tracking value for $stateKey", e)
        }
    }

    fun trackBinaryStateAsync(
        stateKey: String,
        isEnabled: Boolean,
        enabledEvent: String?,
        disabledEvent: String?
    ) {
        scope.launch {
            trackBinaryState(stateKey, isEnabled, enabledEvent, disabledEvent)
        }
    }
}
