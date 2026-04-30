package com.callmap.agenttracker.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.callmap.agenttracker.domain.repository.LocationRepository
import com.callmap.agenttracker.util.Resource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class LocationSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: LocationRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return when (val result = repository.syncPendingLocations()) {
            is Resource.Success -> Result.success()
            is Resource.Error -> {
                if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
            else -> Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "LocationSyncWorker"
    }
}
