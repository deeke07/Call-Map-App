package com.callmap.agenttracker.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.callmap.agenttracker.domain.manager.ServiceManager
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.domain.usecase.location.ShouldTrackLocationUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class LocationScheduleWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val sessionManager: SessionManager,
    private val serviceManager: ServiceManager,
    private val shouldTrackUseCase: ShouldTrackLocationUseCase
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "LocationScheduleAudit"
        private const val TAG = "LocationScheduleWorker"
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            12346, 
            serviceManager.createNotification("Syncing location schedule...")
        )
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Executing expedited tracking audit...")
        
        try {
            // Ensure the worker itself is in foreground so the notification stays visible
            // while we transition to the actual LocationService.
            try {
                setForeground(getForegroundInfo())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set worker to foreground: ${e.message}")
            }

            val settings = sessionManager.getRegistration().first()
            if (settings == null) {
                Log.w(TAG, "No settings found. Stopping tracking.")
                serviceManager.handleServiceLifecycle(false)
                return Result.success()
            }

            val now = System.currentTimeMillis()
            val shouldBeTracking = shouldTrackUseCase(now, settings)

            Log.i(TAG, "Audit Decision: shouldBeTracking=$shouldBeTracking")
            
            // Start or Stop the Foreground Service
            serviceManager.handleServiceLifecycle(shouldBeTracking)
            
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Audit failed: ${e.message}")
            return Result.retry()
        }
    }
}
