package com.callmap.agenttracker.data.manager

import android.content.Context
import android.util.Log
import androidx.work.*
import com.callmap.agenttracker.data.worker.*
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.domain.manager.SyncManager
import com.callmap.agenttracker.domain.usecase.location.NextTriggerTimeCalculator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import com.callmap.agenttracker.util.TrackingLog
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncManagerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessionManager: SessionManager,
    private val nextTriggerCalculator: NextTriggerTimeCalculator,
    private val alarmScheduler: AlarmScheduler
) : SyncManager {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun setupBackgroundSync() {
        CallReconciliationWorker.schedule(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // 0. Observe network status to trigger sync on reconnection
        scope.launch {
            com.callmap.agenttracker.util.NetworkObserver(context).observe().collect { status ->
                if (status == com.callmap.agenttracker.util.NetworkObserver.Status.Available) {
                    TrackingLog.i("SyncManager", "Reconnected — triggering bulk sync")
                    triggerPendingSync()
                }
            }
        }

        // 1. Periodic Data Syncs (Opportunistic)
        enqueuePeriodic<LocationSyncWorker>(LocationSyncWorker.WORK_NAME, constraints)
        enqueuePeriodic<CallSyncWorker>(CallSyncWorker.WORK_NAME, constraints)
        enqueuePeriodic<DeviceEventSyncWorker>("DeviceEventSync_Periodic", constraints)

        // 2. Periodic State Loop (Safety check)
        val stateCheckRequest = PeriodicWorkRequestBuilder<DeviceStateWorker>(15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "DeviceStateCheck_Periodic_Main",
            ExistingPeriodicWorkPolicy.UPDATE,
            stateCheckRequest
        )

        // 3. Kickstart the 2-minute high-frequency state loop immediately
        val initialStateCheck = OneTimeWorkRequestBuilder<DeviceStateWorker>()
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "DeviceStateWorker_Periodic",
            ExistingWorkPolicy.KEEP,
            initialStateCheck
        )
        
        // 4. Kickstart the tracking schedule
        scheduleTrackingAudit()

        // 5. Schedule daily morning health check
        alarmScheduler.scheduleDailyHealthCheck()
    }

    private inline fun <reified T : ListenableWorker> enqueuePeriodic(name: String, constraints: Constraints) {
        val request = PeriodicWorkRequestBuilder<T>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun triggerPendingSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // 1. Location Sync
        val locationRequest = OneTimeWorkRequestBuilder<LocationSyncWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("ImmediateLocationSync", ExistingWorkPolicy.KEEP, locationRequest)

        // 2. Call Sync
        val callRequest = OneTimeWorkRequestBuilder<CallSyncWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(CallSyncWorker.IMMEDIATE_WORK_NAME, ExistingWorkPolicy.KEEP, callRequest)

        // 3. Device Event Sync
        val eventRequest = OneTimeWorkRequestBuilder<DeviceEventSyncWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            DeviceEventSyncWorker.WORK_NAME_IMMEDIATE,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            eventRequest
        )
    }

    override fun scheduleTrackingAudit() {
        TrackingLog.d("SyncManager", "Tracking audit")

        // A. Immediate Enforcement (Expedited Worker)
        // This ensures the service starts NOW if we are inside the window.
        val request = OneTimeWorkRequestBuilder<LocationScheduleWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
            
        WorkManager.getInstance(context).enqueueUniqueWork(
            LocationScheduleWorker.WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )

        // B. Precise Future Transition (AlarmManager)
        // This wakes the device at the EXACT moment tracking should start or stop.
        scope.launch {
            val settings = sessionManager.getRegistration().first()
            val now = System.currentTimeMillis()
            val delay = nextTriggerCalculator.calculateDelay(now, settings)

            if (delay > 0) {
                val triggerAt = now + delay
                TrackingLog.d("SyncManager", "Next transition in ${delay / 1000}s")
                alarmScheduler.scheduleExactAlarm(triggerAt)
            }
        }
    }

    override fun cancelAllSync() {
        Log.w("SyncManager", "Cancelling all background sync jobs")
        WorkManager.getInstance(context).cancelAllWork()
        alarmScheduler.cancelAllAlarms()
    }
}
