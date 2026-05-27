package com.callmap.agenttracker.presentation.home

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.callmap.agenttracker.data.worker.CallSyncWorker
import com.callmap.agenttracker.data.worker.LocationSyncWorker
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.domain.model.RegistrationResult
import com.callmap.agenttracker.service.LocationService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

import com.callmap.agenttracker.presentation.permissions.SpecialPermissionManager

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val fetchConfigUseCase: com.callmap.agenttracker.domain.usecase.FetchConfigUseCase,
    private val deviceSimManager: com.callmap.agenttracker.domain.manager.DeviceSimManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        observeRegistration()
        setupBackgroundSync()
        refreshConfig()
        checkLocationStatus()
        syncSims()
    }

    private fun syncSims() {
        viewModelScope.launch {
            try {
                deviceSimManager.syncSimsWithBackend()
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error syncing SIMs", e)
            }
        }
    }

    fun checkLocationStatus() {
        val isEnabled = SpecialPermissionManager.isLocationHardwareEnabled(context)
        _state.update { it.copy(isLocationEnabled = isEnabled) }
    }

    fun openLocationSettings() {
        SpecialPermissionManager.openLocationSettings(context)
    }

    fun refreshConfig() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            fetchConfigUseCase()
            _state.update { it.copy(isLoading = false) }
        }
    }

    private fun observeRegistration() {
        viewModelScope.launch {
            sessionManager.getRegistration().collect { registration ->
                _state.update { it.copy(registration = registration) }
            }
        }
    }



    private fun setupBackgroundSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // 1. Location Sync
        val locationSyncRequest = PeriodicWorkRequestBuilder<LocationSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            LocationSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            locationSyncRequest
        )

        // 2. Call Sync (With embedded recovery)
        val callSyncRequest = PeriodicWorkRequestBuilder<CallSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            CallSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            callSyncRequest
        )
    }

    // Existing registrationState for backward compatibility if needed, 
    // but preferred to use 'state'
    val registrationState: StateFlow<RegistrationResult?> = sessionManager.getRegistration()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )
}
