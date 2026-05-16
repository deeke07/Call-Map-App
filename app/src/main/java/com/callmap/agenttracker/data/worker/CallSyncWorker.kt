package com.callmap.agenttracker.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.callmap.agenttracker.domain.repository.CallRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class CallSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val callRepository: CallRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("CallSyncWorker", "Starting sync and orphan recovery...")

        return try {
            // 1. Recover recordings for logs in DB that missed them during real-time processing
            callRepository.recoverOrphanFiles(applicationContext)

            // 2. Batch upload pending logs
            val uploadResult = callRepository.uploadPendingCallLogs(applicationContext)

            if (uploadResult.isSuccess) {
                Log.d("CallSyncWorker", "Sync completed successfully")
                Result.success()
            } else {
                Log.e("CallSyncWorker", "Sync failed, will retry", uploadResult.exceptionOrNull())
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e("CallSyncWorker", "Unexpected error during sync", e)
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "CallSyncWorker_Periodic"
        const val IMMEDIATE_WORK_NAME = "CallSyncWorker_Immediate"
    }
}