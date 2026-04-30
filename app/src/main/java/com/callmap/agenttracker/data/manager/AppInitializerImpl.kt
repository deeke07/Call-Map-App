package com.callmap.agenttracker.data.manager

import android.content.Context
import android.util.Log
import com.callmap.agenttracker.domain.manager.AppInitializer
import com.callmap.agenttracker.domain.manager.ServiceManager
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.domain.manager.SyncManager
import com.callmap.agenttracker.domain.repository.CallRepository
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
    private val callRepository: CallRepository
) : AppInitializer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun init() {
        Log.i("AppInitializer", "Initializing application background systems...")
        
        // 1. Setup periodic background sync
        syncManager.setupBackgroundSync()

        // 2. Trigger immediate sync for any pending data left from previous session
        syncManager.triggerPendingSync()

        scope.launch {
            // 3. Restore services based on last saved config
            try {
                val registration = sessionManager.getRegistration().first()
                if (registration != null) {
                    Log.d("AppInitializer", "Restoring services: Tracking=${registration.trackingEnabled}")
                    serviceManager.handleServiceLifecycle(registration.trackingEnabled)
                } else {
                    Log.w("AppInitializer", "No registration found, services not started")
                }
            } catch (e: Exception) {
                Log.e("AppInitializer", "Error restoring services", e)
            }

            // 4. Recover orphan recording files from storage
            try {
                Log.d("AppInitializer", "Starting orphan file recovery...")
                callRepository.recoverOrphanFiles(context)
            } catch (e: Exception) {
                Log.e("AppInitializer", "Error recovering orphan files", e)
            }
        }
    }
}
