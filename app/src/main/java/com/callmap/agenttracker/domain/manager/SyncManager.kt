package com.callmap.agenttracker.domain.manager

interface SyncManager {
    fun setupBackgroundSync()
    fun triggerPendingSync()
    fun scheduleTrackingAudit()
}
