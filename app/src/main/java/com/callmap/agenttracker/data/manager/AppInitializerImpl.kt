package com.callmap.agenttracker.data.manager

import android.content.Context
import android.util.Log
import com.callmap.agenttracker.domain.manager.AppInitializer
import com.callmap.agenttracker.domain.manager.DeviceSimManager
import com.callmap.agenttracker.domain.manager.ServiceManager
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.domain.manager.SyncManager
import com.callmap.agenttracker.domain.repository.CallRepository
import com.callmap.agenttracker.domain.usecase.location.ShouldTrackLocationUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppInitializerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncManager: SyncManager,
    private val serviceManager: ServiceManager,
    private val sessionManager: SessionManager,
    private val deviceSimManager: DeviceSimManager,
    private val callRepository: CallRepository,
    private val shouldTrackLocationUseCase: ShouldTrackLocationUseCase
) : AppInitializer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun init() {
        Log.i("AppInitializer", "Initializing application background systems...")
        
        // 0. Ensure Notification Channels exist
        serviceManager.setupNotificationChannels()

        // 1. Setup periodic background sync & precise alarms
        syncManager.setupBackgroundSync()

        // 2. Trigger immediate sync for any pending data
        syncManager.triggerPendingSync()

        scope.launch {
            // 3. Immediate Tracking Enforcement
            // Start the service directly if we are currently in a tracking window.
            // This ensures the notification appears immediately after boot/restart
            // without waiting for WorkManager or Alarms to fire.
            try {
                val settings = sessionManager.getRegistration().first()
                val now = System.currentTimeMillis()
                val shouldBeTracking = shouldTrackLocationUseCase(now, settings)
                
                Log.i("AppInitializer", "Direct Tracking Check: shouldBeTracking=$shouldBeTracking")
                serviceManager.handleServiceLifecycle(shouldBeTracking)
            } catch (e: Exception) {
                Log.e("AppInitializer", "Error during immediate tracking check", e)
            }

            // 4. Recover orphan recording files
            try {
                Log.d("AppInitializer", "Starting orphan file recovery...")
                callRepository.recoverOrphanFiles(context)
            } catch (e: Exception) {
                Log.e("AppInitializer", "Error recovering orphan files", e)
            }
        }
    }
}
