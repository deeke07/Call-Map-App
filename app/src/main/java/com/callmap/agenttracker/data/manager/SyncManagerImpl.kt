package com.callmap.agenttracker.data.manager

import android.content.Context
import androidx.work.*
import com.callmap.agenttracker.data.worker.CallSyncWorker
import com.callmap.agenttracker.data.worker.DeviceEventSyncWorker
import com.callmap.agenttracker.data.worker.DeviceStateWorker
import com.callmap.agenttracker.data.worker.LocationSyncWorker
import com.callmap.agenttracker.domain.manager.SyncManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SyncManager {

    override fun setupBackgroundSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Location Periodic Sync
        val locationSyncRequest = PeriodicWorkRequestBuilder<LocationSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            LocationSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            locationSyncRequest
        )

        // Call Periodic Sync
        val callSyncRequest = PeriodicWorkRequestBuilder<CallSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            CallSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            callSyncRequest
        )

        // Device Events Periodic Sync
        val eventSyncRequest = PeriodicWorkRequestBuilder<DeviceEventSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "DeviceEventSync_Periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            eventSyncRequest
        )

        // Periodic Device State Check - check frequently to catch permission/state changes
        val stateCheckRequest = PeriodicWorkRequestBuilder<DeviceStateWorker>(15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "DeviceStateCheck_Periodic",
            ExistingPeriodicWorkPolicy.UPDATE, // Use UPDATE to ensure the new 2-min loop is active
            stateCheckRequest
        )
        
        // Kickstart a one-time check immediately for debugging/startup
        val kickstartRequest = OneTimeWorkRequestBuilder<DeviceStateWorker>().build()
        WorkManager.getInstance(context).enqueue(kickstartRequest)
    }

    override fun triggerPendingSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val locationRequest = OneTimeWorkRequestBuilder<LocationSyncWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "ImmediateLocationSync",
            ExistingWorkPolicy.REPLACE,
            locationRequest
        )

        val callRequest = OneTimeWorkRequestBuilder<CallSyncWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            CallSyncWorker.IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            callRequest
        )

        val eventRequest = OneTimeWorkRequestBuilder<DeviceEventSyncWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "DeviceEventSync_Immediate",
            ExistingWorkPolicy.REPLACE,
            eventRequest
        )
    }
}
