package com.callmap.agenttracker.data.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * Stub class for a deprecated worker. 
 * WorkManager sometimes keeps old tasks in its internal database across app updates.
 * This class ensures we don't get ClassNotFoundException.
 */
class TrackingWatchdogWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.w("TrackingWatchdogWorker", "Deprecated worker triggered. Cancelling itself.")
        WorkManager.getInstance(applicationContext).cancelUniqueWork("TrackingWatchdog")
        return Result.success()
    }
}
