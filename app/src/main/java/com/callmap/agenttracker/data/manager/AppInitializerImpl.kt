package com.callmap.agenttracker.data.manager

import android.content.Context
import android.util.Log
import com.callmap.agenttracker.domain.manager.AppInitializer
import com.callmap.agenttracker.domain.manager.DeviceSimManager
import com.callmap.agenttracker.domain.manager.ServiceManager
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.domain.manager.SyncManager
import com.callmap.agenttracker.domain.repository.CallRepository
import com.callmap.agenttracker.domain.repository.LocationRepository
import com.callmap.agenttracker.domain.usecase.location.ShouldTrackLocationUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.callmap.agenttracker.util.TrackingLog
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
    private val shouldTrackLocationUseCase: ShouldTrackLocationUseCase,
    private val locationRepository: LocationRepository
) : AppInitializer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastInitAtMs = 0L

    override fun init() {
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (now - lastInitAtMs < INIT_DEBOUNCE_MS) {
                TrackingLog.d("AppInitializer", "Init skipped (debounced)")
                return
            }
            lastInitAtMs = now
        }
        TrackingLog.i("AppInitializer", "Background systems init")
        
        // 0. Ensure Notification Channels exist
        serviceManager.setupNotificationChannels()

        // 1. Setup periodic background sync & precise alarms
        syncManager.setupBackgroundSync()

        scope.launch {
            try {
                val imported = locationRepository.importSpilloverLocations()
                if (imported > 0) Log.i("AppInitializer", "Recovered $imported locations from spillover file")
            } catch (e: Exception) {
                Log.e("AppInitializer", "Spillover import failed", e)
            }
        }

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
                
                TrackingLog.d("AppInitializer", "shouldBeTracking=$shouldBeTracking")
                serviceManager.handleServiceLifecycle(shouldBeTracking)
            } catch (e: Exception) {
                Log.e("AppInitializer", "Error during immediate tracking check", e)
            }

            // 4. Recover orphan recording files
            try {
                TrackingLog.d("AppInitializer", "Orphan file recovery")
                callRepository.recoverOrphanFiles(context)
            } catch (e: Exception) {
                Log.e("AppInitializer", "Error recovering orphan files", e)
            }
        }
    }

    companion object {
        private const val INIT_DEBOUNCE_MS = 15_000L
    }
}
