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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: SessionManager,
    private val nextTriggerCalculator: NextTriggerTimeCalculator,
    private val alarmScheduler: AlarmScheduler
) : SyncManager {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun setupBackgroundSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // 1. Periodic Data Syncs (Opportunistic)
        enqueuePeriodic<LocationSyncWorker>(LocationSyncWorker.WORK_NAME, constraints)
        enqueuePeriodic<CallSyncWorker>(CallSyncWorker.WORK_NAME, constraints)
        enqueuePeriodic<DeviceEventSyncWorker>("DeviceEventSync_Periodic", constraints)

        // 2. Periodic State Loop (Safety check)
        val stateCheckRequest = PeriodicWorkRequestBuilder<DeviceStateWorker>(15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "DeviceStateCheck_Periodic",
            ExistingPeriodicWorkPolicy.UPDATE,
            stateCheckRequest
        )
        
        // 3. Kickstart the tracking schedule
        scheduleTrackingAudit()
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
        WorkManager.getInstance(context).enqueueUniqueWork("ImmediateDeviceEventSync", ExistingWorkPolicy.REPLACE, eventRequest)
    }

    override fun scheduleTrackingAudit() {
        Log.i("SyncManager", "Enforcing Tracking Audit...")

        // A. Immediate Enforcement (Expedited Worker)
        // This ensures the service starts NOW if we are inside the window.
        val request = OneTimeWorkRequestBuilder<LocationScheduleWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
            
        WorkManager.getInstance(context).enqueueUniqueWork(
            LocationScheduleWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
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
                Log.i("SyncManager", "Next transition in ${delay/1000}s. Scheduling Precise Alarm.")
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
