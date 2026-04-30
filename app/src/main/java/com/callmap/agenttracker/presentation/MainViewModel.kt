package com.callmap.agenttracker.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.presentation.permissions.PermissionManager
import com.callmap.agenttracker.presentation.permissions.SpecialPermissionManager
import com.callmap.agenttracker.service.MyAccessibilityService
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _startDestination = MutableStateFlow<String?>(null)
    val startDestination: StateFlow<String?> = _startDestination

    fun checkState(context: Context) {
        viewModelScope.launch {
            val registration = sessionManager.getRegistration().first()
            if (registration == null) {
                _startDestination.value = "register"
            } else {
                val runtimeGranted = PermissionManager.areAllPermissionsGranted(context, PermissionManager.runtimePermissions)
                val accessibilityEnabled = SpecialPermissionManager.isAccessibilityServiceEnabled(context, MyAccessibilityService::class.java)
                val batteryIgnored = SpecialPermissionManager.isBatteryOptimizationIgnored(context)

                if (runtimeGranted && accessibilityEnabled && batteryIgnored) {
                    _startDestination.value = "home"
                } else {
                    _startDestination.value = "permissions"
                }
            }
        }
    }
}
