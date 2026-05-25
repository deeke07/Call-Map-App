package com.callmap.agenttracker.presentation.home

import com.callmap.agenttracker.domain.model.RegistrationResult

data class HomeState(
    val registration: RegistrationResult? = null,
    val isTrackingActive: Boolean = false,
    val isLocationEnabled: Boolean = true,
    val isLoading: Boolean = false
)
