package com.callmap.agenttracker.domain.manager

import com.callmap.agenttracker.domain.model.RegistrationResult
import kotlinx.coroutines.flow.Flow

interface SessionManager {
    suspend fun saveRegistration(registration: RegistrationResult)
    fun getRegistration(): Flow<RegistrationResult?>
    suspend fun clearSession()
    
    // State Tracking for Deduplication
    fun getDeviceStates(): Flow<Map<String, String>>
    suspend fun updateDeviceState(key: String, value: String)
}
