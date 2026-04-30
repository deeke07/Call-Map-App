package com.callmap.agenttracker.presentation.register_device

import com.callmap.agenttracker.domain.model.RegistrationResult

data class RegisterDeviceState(
    val isLoading: Boolean = false,
    val success: RegistrationResult? = null,
    val error: String = "",
    val usernameError: String? = null,
    val passwordError: String? = null
)
