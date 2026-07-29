package com.callmap.agenttracker.presentation.permissions

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PermissionsViewModel @Inject constructor() : ViewModel() {

    private val _currentStep = mutableStateOf(PermissionStep.RUNTIME)
    val currentStep: State<PermissionStep> = _currentStep

    fun nextStep() {
        _currentStep.value = when (_currentStep.value) {
            PermissionStep.RUNTIME -> PermissionStep.SPECIAL
            PermissionStep.SPECIAL -> PermissionStep.COMPLETED
            PermissionStep.COMPLETED -> PermissionStep.COMPLETED
        }
    }
}

enum class PermissionStep {
    RUNTIME,
    SPECIAL,
    COMPLETED
}
