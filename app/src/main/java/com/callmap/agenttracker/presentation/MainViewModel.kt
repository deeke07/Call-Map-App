package com.callmap.agenttracker.presentation

import android.Manifest
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.callmap.agenttracker.data.manager.DeviceStateManager
import com.callmap.agenttracker.domain.manager.EventManager
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.domain.repository.DeviceEventRepository
import com.callmap.agenttracker.presentation.permissions.PermissionManager
import com.callmap.agenttracker.presentation.permissions.SpecialPermissionManager
import com.callmap.agenttracker.service.MyAccessibilityService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val stateManager: DeviceStateManager,
    private val eventRepository: DeviceEventRepository
) : ViewModel() {

    private val _startDestination = MutableStateFlow<String?>(null)
    val startDestination: StateFlow<String?> = _startDestination

    init {
        observeSession()
    }

    private fun observeSession() {
        viewModelScope.launch {
            sessionManager.getRegistration().collectLatest { registration ->
                // Prevent initial flicker by only reacting to session changes
                // after the initial checkState() has determined the starting point.
                val current = _startDestination.value ?: return@collectLatest

                if (registration == null && current == "home") {
                    // Redirect to register only if we were previously on home (logout)
                    _startDestination.value = "register"
                } else if (registration != null && current == "register") {
                    // Transition to home if registration succeeds while on register screen
                    _startDestination.value = "home"
                }
            }
        }
    }

    suspend fun isRegistered(): Boolean {
        return sessionManager.getRegistration().first() != null
    }

    private suspend fun logPermissionStates(context: Context) {
        // Fix: Small delay to ensure OS permission state is synchronized after returning from Settings
        delay(500)

        // Use exact name mapping from DeviceStateWorker to ensure transitions are detected correctly.
        val permissionMap = mutableMapOf(
            Manifest.permission.RECORD_AUDIO to "RECORD_AUDIO",
            Manifest.permission.ACCESS_FINE_LOCATION to "LOCATION",
            Manifest.permission.READ_PHONE_STATE to "PHONE_STATE",
            Manifest.permission.READ_CALL_LOG to "CALL_LOG",
            Manifest.permission.READ_CONTACTS to "CONTACTS",
            Manifest.permission.CALL_PHONE to "CALL_PHONE",
            @Suppress("DEPRECATION")
            Manifest.permission.PROCESS_OUTGOING_CALLS to "OUTGOING_CALLS"
        )

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            permissionMap[Manifest.permission.ACCESS_BACKGROUND_LOCATION] = "BACKGROUND_LOCATION"
        }

        permissionMap.forEach { (perm, name) ->
            val enabledEvent = if (name == "LOCATION") EventManager.LOCATION_ENABLED else EventManager.PERMISSION_ENABLED
            val disabledEvent = if (name == "LOCATION") EventManager.LOCATION_DISABLED else EventManager.PERMISSION_DISABLED

            stateManager.trackBinaryState(
                stateKey = "perm",
                isEnabled = PermissionManager.isPermissionGranted(context, perm),
                enabledEvent = enabledEvent,
                disabledEvent = disabledEvent,
                permissionName = name
            )
        }

        // Special Permissions
        stateManager.trackBinaryState(
            stateKey = "location_hardware",
            isEnabled = SpecialPermissionManager.isLocationHardwareEnabled(context),
            enabledEvent = EventManager.LOCATION_ENABLED,
            disabledEvent = EventManager.LOCATION_DISABLED
        )

        stateManager.trackBinaryState(
            stateKey = "battery_optimization",
            // Worker uses isEnabled = true when optimization is ON
            isEnabled = !SpecialPermissionManager.isBatteryOptimizationIgnored(context),
            enabledEvent = EventManager.BATTERY_OPTIMIZATION_ENABLED,
            disabledEvent = null
        )

        // Force immediate sync of the logged events to the server
        eventRepository.syncPendingEvents()
    }

    fun checkState(context: Context) {
        viewModelScope.launch {
            val registration = sessionManager.getRegistration().first()
            val current = _startDestination.value
            val next = startupDestination(
                registered = registration != null,
                corePermissionsGranted = areRequiredPermissionsGranted(context, includeAccessibility = false),
                accessibilitySelected = SpecialPermissionManager.isAccessibilityServiceEnabled(
                    context, MyAccessibilityService::class.java
                ),
                current = current
            )

            if (current != next) {
                _startDestination.value = next
            }
            // Network delivery of diagnostic events must not delay session routing.
            logPermissionStates(context)
        }
    }

    fun completeWelcome(context: Context) {
        viewModelScope.launch {
            if (areRequiredPermissionsGranted(context)) {
                _startDestination.value = "register"
            } else {
                _startDestination.value = "permissions"
            }
        }
    }

    private fun areRequiredPermissionsGranted(context: Context, includeAccessibility: Boolean = true): Boolean {
        val runtimeGranted = PermissionManager.areAllPermissionsGranted(context, PermissionManager.runtimePermissions)
        val accessibilityEnabled = SpecialPermissionManager.isAccessibilityServiceEnabled(context, MyAccessibilityService::class.java)
        val batteryIgnored = SpecialPermissionManager.isBatteryOptimizationIgnored(context)
        val allFilesAccess = SpecialPermissionManager.isManageExternalStorageGranted(context)
        val gpsEnabled = SpecialPermissionManager.isLocationHardwareEnabled(context)

        return runtimeGranted && (!includeAccessibility || accessibilityEnabled) && batteryIgnored && allFilesAccess && gpsEnabled
    }
}
