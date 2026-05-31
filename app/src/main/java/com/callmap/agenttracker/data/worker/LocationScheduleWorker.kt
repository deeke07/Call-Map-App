package com.callmap.agenttracker.data.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.callmap.agenttracker.domain.manager.ServiceManager
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.domain.usecase.location.ShouldTrackLocationUseCase
import dagger.assisted.Assisted
import com.callmap.agenttracker.util.TrackingLog
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

    override suspend fun doWork(): Result {
        TrackingLog.d(TAG, "Tracking audit")
        
        try {
            val settings = sessionManager.getRegistration().first()
            if (settings == null) {
                Log.w(TAG, "No settings found. Stopping tracking.")
                serviceManager.handleServiceLifecycle(false)
                return Result.success()
            }

            val now = System.currentTimeMillis()
            val shouldBeTracking = shouldTrackUseCase(now, settings)

            TrackingLog.d(TAG, "shouldBeTracking=$shouldBeTracking")
            
            // Start or Stop the Foreground Service
            serviceManager.handleServiceLifecycle(shouldBeTracking)
            
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Audit failed: ${e.message}")
            return Result.retry()
        }
    }
}
