package com.callmap.agenttracker.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.callmap.agenttracker.domain.repository.DeviceEventRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class DeviceEventSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: DeviceEventRepository
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME_PERIODIC = "DeviceEventSync_Periodic"
        const val WORK_NAME_IMMEDIATE = "DeviceEventSync_Immediate"
    }

    override suspend fun doWork(): Result {
        val result = repository.syncPendingEvents()
        return if (result.isSuccess) {
            Result.success()
        } else {
            if (runAttemptCount < 5) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
