package com.callmap.agenttracker.domain.repository

import com.callmap.agenttracker.data.local.entity.DeviceEventEntity

interface DeviceEventRepository {
    suspend fun logEvent(
        eventType: String,
        permissionName: String? = null,
        metadata: Map<String, Any>? = null
    )
    suspend fun syncPendingEvents(): Result<Unit>
    suspend fun cleanupSyncedEvents()
}
