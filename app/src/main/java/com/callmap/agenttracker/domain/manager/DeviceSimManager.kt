package com.callmap.agenttracker.domain.manager

import com.callmap.agenttracker.data.remote.dto.SimRequestDto

interface DeviceSimManager {
    suspend fun getActiveSims(): List<SimRequestDto>
    suspend fun syncSimsWithBackend(): Result<Unit>
    suspend fun getSimUuidForSlot(slotIndex: Int): String?
    suspend fun getCarrierNameForSlot(slotIndex: Int): String?
    suspend fun getSimSlotFromSubscriptionId(subId: String?): Int?
}
