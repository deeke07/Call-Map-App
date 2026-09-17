package com.callmap.agenttracker.presentation.home

import com.callmap.agenttracker.domain.model.RegistrationResult
import com.callmap.agenttracker.presentation.permissions.AccessibilityStatus

data class HomeState(
    val registration: RegistrationResult? = null,
    val isTrackingActive: Boolean = false,
    val isLocationEnabled: Boolean = true,
    val isLocationPermissionGranted: Boolean = true,
    val accessibilityStatus: AccessibilityStatus = AccessibilityStatus.UNKNOWN,
    val isLoading: Boolean = false
)
