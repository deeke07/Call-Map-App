package com.callmap.agenttracker.data.repository

import android.content.Context
import android.util.Log
import androidx.work.*
import com.callmap.agenttracker.data.local.dao.DeviceEventDao
import com.callmap.agenttracker.data.local.entity.DeviceEventEntity
import com.callmap.agenttracker.data.local.entity.SyncStatus
import com.callmap.agenttracker.data.remote.api.CallApi
import com.callmap.agenttracker.data.remote.dto.DeviceEventRequest
import com.callmap.agenttracker.data.worker.DeviceEventSyncWorker
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.domain.repository.DeviceEventRepository
import com.callmap.agenttracker.util.NetworkObserver
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceEventRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DeviceEventDao,
    private val api: CallApi,
    private val sessionManager: SessionManager,
    private val networkObserver: NetworkObserver,
    private val gson: Gson
) : DeviceEventRepository {

    private val syncMutex = Mutex()
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    companion object {
        private const val TAG = "DeviceEventRepo"
        private const val BATCH_SIZE = 20
        private const val MAX_RETRY_COUNT = 5
    }

    override suspend fun logEvent(
        eventType: String,
        permissionName: String?,
        metadata: Map<String, Any>?
    ) {
        try {
            val registration = sessionManager.getRegistration().first()
            val deviceUuid = registration?.deviceUuid ?: "unknown"

            val event = DeviceEventEntity(
                deviceUuid = deviceUuid,
                eventType = eventType,
                eventTime = isoFormat.format(Date()),
                permissionName = permissionName,
                metadata = metadata?.let { gson.toJson(it) }
            )

            val id = dao.insertEvent(event)
            Log.i(TAG, "Logged Transition: $eventType ($permissionName) ID: $id")

            // Always trigger immediate sync attempt if online
            if (networkObserver.isConnected()) {
                triggerImmediateSync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log event: $eventType", e)
        }
    }

    override suspend fun syncPendingEvents(): Result<Unit> {
        if (!networkObserver.isConnected()) return Result.failure(Exception("Offline"))

        return try {
            syncMutex.withLock {
                var someFailed = false
                while (true) {
                    val pendingEvents = dao.getUnsyncedEvents(SyncStatus.PENDING, BATCH_SIZE)
                    if (pendingEvents.isEmpty()) {
                        break
                    }

                    for (event in pendingEvents) {
                        val successResult = uploadEvent(event)
                        when (successResult) {
                            UploadResult.SUCCESS -> dao.deleteEventById(event.id)
                            UploadResult.PERMANENT_FAILURE -> dao.updateSyncStatus(event.id, SyncStatus.FAILED)
                            UploadResult.RETRYABLE_FAILURE -> {
                                someFailed = true
                                val newRetryCount = event.retryCount + 1
                                if (newRetryCount >= MAX_RETRY_COUNT) {
                                    dao.updateSyncStatus(event.id, SyncStatus.FAILED)
                                } else {
                                    dao.updateRetryCount(event.id, newRetryCount)
                                }
                            }
                        }
                    }
                }
                if (someFailed) {
                    Result.failure<Unit>(Exception("Some events failed to sync"))
                } else {
                    Result.success(Unit)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing events", e)
            Result.failure(e)
        }
    }

    private enum class UploadResult { SUCCESS, PERMANENT_FAILURE, RETRYABLE_FAILURE }

    private suspend fun uploadEvent(event: DeviceEventEntity): UploadResult {
        if (event.deviceUuid == "unknown" || event.deviceUuid.isBlank()) return UploadResult.PERMANENT_FAILURE

        return try {
            val metadataMap: Map<String, Any>? = event.metadata?.let {
                val type = object : TypeToken<Map<String, Any>>() {}.type
                gson.fromJson(it, type)
            }

            val request = DeviceEventRequest(
                device_uuid = event.deviceUuid,
                event_type = event.eventType,
                event_time = event.eventTime,
                permission_name = event.permissionName,
                metadata = metadataMap
            )

            val response = api.submitDeviceEvent(request)
            
            if (response.isSuccessful) {
                Log.i(TAG, "Synced Event: ${event.eventType} ${event.permissionName}")
                UploadResult.SUCCESS
            } else if (response.code() == 422) {
                Log.e(TAG, "Discarding 422 (Invalid type/UUID): ${event.eventType}")
                UploadResult.PERMANENT_FAILURE
            } else {
                UploadResult.RETRYABLE_FAILURE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network exception syncing event", e)
            UploadResult.RETRYABLE_FAILURE
        }
    }

    override suspend fun cleanupSyncedEvents() {
        val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
        dao.cleanupOldEvents(sevenDaysAgo)
    }

    private fun triggerImmediateSync() {
        val workRequest = OneTimeWorkRequestBuilder<DeviceEventSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "DeviceEventSync_Immediate",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            workRequest
        )
    }
}
