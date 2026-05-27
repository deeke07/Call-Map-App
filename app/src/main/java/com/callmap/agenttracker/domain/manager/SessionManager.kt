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

    suspend fun saveSimUuid(simSlot: Int, uuid: String)
    suspend fun getSimUuid(simSlot: Int): String?
    
    suspend fun saveSimSubIdMapping(subId: String, simSlot: Int)
    suspend fun getSlotFromSubIdMapping(subId: String): Int?
}
