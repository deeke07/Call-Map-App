package com.callmap.agenttracker.presentation.register_device

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.callmap.agenttracker.domain.use_case.RegisterDeviceUseCase
import com.callmap.agenttracker.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@HiltViewModel
class RegisterDeviceViewModel @Inject constructor(
    private val registerDeviceUseCase: RegisterDeviceUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(RegisterDeviceState())
    val state: StateFlow<RegisterDeviceState> = _state.asStateFlow()

    private val _passcode = mutableStateOf("")
    val passcode: State<String> = _passcode

    private val _email = mutableStateOf("")
    val email: State<String> = _email

    fun onPasscodeChange(value: String) {
        _passcode.value = value
        _state.value = _state.value.copy(usernameError = null, error = "")
    }

    fun onEmailChange(value: String) {
        _email.value = value
        _state.value = _state.value.copy(passwordError = null, error = "")
    }

    fun register() {
        if (email.value.isBlank()) {
            _state.value = _state.value.copy(passwordError = "Email cannot be empty")
            return
        }
        if (passcode.value.isBlank()) {
            _state.value = _state.value.copy(usernameError = "Passcode cannot be empty")
            return
        }

        registerDeviceUseCase(passcode.value, email.value).onEach { result ->
            when (result) {
                is Resource.Success -> {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        success = result.data,
                        error = ""
                    )
                }
                is Resource.Error -> {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = result.message ?: "An unexpected error occurred"
                    )
                }
                is Resource.Loading -> {
                    _state.value = _state.value.copy(isLoading = true, error = "")
                }
            }
        }.launchIn(viewModelScope)
    }
}
