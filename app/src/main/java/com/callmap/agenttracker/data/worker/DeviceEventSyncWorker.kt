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
