package com.callmap.agenttracker.data.manager

import com.callmap.agenttracker.domain.manager.EventManager
import com.callmap.agenttracker.domain.repository.DeviceEventRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventManagerImpl @Inject constructor(
    private val repository: DeviceEventRepository
) : EventManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun logEvent(
        eventType: String,
        permissionName: String?,
        metadata: Map<String, Any>?
    ) {
        scope.launch {
            repository.logEvent(eventType, permissionName, metadata)
        }
    }
}
