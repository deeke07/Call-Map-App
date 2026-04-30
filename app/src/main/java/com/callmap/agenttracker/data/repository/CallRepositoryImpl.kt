package com.callmap.agenttracker.data.repository

import android.util.Log
import com.callmap.agenttracker.data.local.dao.CallLogDao
import com.callmap.agenttracker.data.local.entity.CallLogEntity
import com.callmap.agenttracker.data.local.entity.SyncStatus
import com.callmap.agenttracker.data.remote.api.CallApi
import com.callmap.agenttracker.domain.repository.CallRepository
import com.callmap.agenttracker.util.file.FileUtils
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

class CallRepositoryImpl @Inject constructor(
    private val api: CallApi,
    private val dao: CallLogDao,
    private val networkObserver: com.callmap.agenttracker.util.NetworkObserver
) : CallRepository {

    private val syncMutex = Mutex()

    companion object {
        private const val TAG = "CallRepository"
        private const val MAX_RETRY_COUNT = 3
        private const val BATCH_SIZE = 5
    }

    override suspend fun saveCallLog(callLog: CallLogEntity): Long {
        return dao.insertCallLog(callLog)
    }

    override suspend fun exists(uniqueId: String): Boolean {
        return dao.exists(uniqueId)
    }

    override suspend fun uploadCallLog(callLog: CallLogEntity, audioFile: File?): Result<Unit> {
        if (!networkObserver.isConnected()) {
            Log.w(TAG, "Offline: Skipping call upload for ${callLog.clientNumber}. Will retry later.")
            return Result.failure(Exception("No internet connection"))
        }

        return try {
            val apiModel = com.callmap.agenttracker.data.mapper.CallMapper.mapCallToApiModel(callLog)

            val deviceUuid = callLog.deviceUuid.toRequestBody("text/plain".toMediaTypeOrNull())
            val clientNumber = callLog.clientNumber.toRequestBody("text/plain".toMediaTypeOrNull())
            val callType = apiModel.call_type.toRequestBody("text/plain".toMediaTypeOrNull())
            val callDuration = apiModel.call_duration.toString().toRequestBody("text/plain".toMediaTypeOrNull())
            val callStartedAt = callLog.callStartedAt.toRequestBody("text/plain".toMediaTypeOrNull())
            val callEndedAt = callLog.callEndedAt.toRequestBody("text/plain".toMediaTypeOrNull())
            val callAnsweredAt = apiModel.call_answered_at?.toRequestBody("text/plain".toMediaTypeOrNull())
            val callerName = (callLog.callerName ?: "Unknown").toRequestBody("text/plain".toMediaTypeOrNull())
            val durationMismatch = (if (callLog.durationMismatch) "1" else "0").toRequestBody("text/plain".toMediaTypeOrNull())
            val wasOnHold = (if (callLog.wasOnHold) "1" else "0").toRequestBody("text/plain".toMediaTypeOrNull())
            val interruptedNumbers = callLog.interruptedNumbers?.toRequestBody("text/plain".toMediaTypeOrNull())
            val spotSettingVersion = callLog.spotSettingVersion?.toRequestBody("text/plain".toMediaTypeOrNull())
            val apkVersion = callLog.apkVersion?.toRequestBody("text/plain".toMediaTypeOrNull())
            val latitude = callLog.latitude?.toRequestBody("text/plain".toMediaTypeOrNull())
            val longitude = callLog.longitude?.toRequestBody("text/plain".toMediaTypeOrNull())
            val batteryLevel = callLog.batteryLevel?.toRequestBody("text/plain".toMediaTypeOrNull())
            val metaData = callLog.metaData?.toRequestBody("text/plain".toMediaTypeOrNull())

            // Only create recording part if the mapper decided to include it
            val recordingPart = apiModel.call_recording_file?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    val requestFile = file.asRequestBody("audio/x-wav".toMediaTypeOrNull())
                    MultipartBody.Part.createFormData("call_recording_file", file.name, requestFile)
                } else null
            }

            val response = api.submitCallLog(
                deviceUuid, clientNumber, callType, callDuration, callStartedAt, callEndedAt,
                callAnsweredAt, callerName, durationMismatch, wasOnHold, interruptedNumbers,
                spotSettingVersion, apkVersion, latitude, longitude, batteryLevel, metaData, recordingPart
            )

            if (response.isSuccessful) {
                dao.updateSyncStatus(callLog.uniqueId, SyncStatus.SYNCED)
                Log.i(TAG, "Uploaded Call Success: ${response.body()}")
                Result.success(Unit)
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Upload failed for ${callLog.uniqueId}: Code ${response.code()} - $errorBody")
                
                val newRetryCount = callLog.retryCount + 1
                if (newRetryCount >= MAX_RETRY_COUNT) {
                    dao.updateSyncStatus(callLog.uniqueId, SyncStatus.FAILED)
                    Log.e(TAG, "Max retries exceeded for ${callLog.uniqueId}")
                } else {
                    dao.updateRetryCount(callLog.uniqueId, newRetryCount)
                    Log.w(TAG, "Retrying later for ${callLog.uniqueId}, count: $newRetryCount")
                }
                Result.failure(Exception("Upload failed: ${response.code()} - $errorBody"))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Error uploading call log ${callLog.uniqueId}", e)
            val newRetryCount = callLog.retryCount + 1
            if (newRetryCount >= MAX_RETRY_COUNT) {
                dao.updateSyncStatus(callLog.uniqueId, SyncStatus.FAILED)
            } else {
                dao.updateRetryCount(callLog.uniqueId, newRetryCount)
            }
            Result.failure(e)
        }
    }

    override suspend fun recoverOrphanFiles(context: android.content.Context) {
        // 1. Recover recordings for existing logs in DB that are missing paths
        val logsMissingRecordings = dao.getLogsMissingRecordings()
        if (logsMissingRecordings.isNotEmpty()) {
            logsMissingRecordings.forEach { log ->
                val bestMatch = FileUtils.findBestSystemRecording(
                    logEndTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(log.callEndedAt)?.time ?: 0L,
                    systemDurationSec = log.callDuration,
                    clientNumber = log.clientNumber,
                    callerName = log.callerName
                )

                if (bestMatch != null) {
                    Log.i(TAG, "Recovered recording for ${log.clientNumber}")
                    dao.updateRecordingPath(log.uniqueId, bestMatch.absolutePath)
                }
            }
        }

        // 2. Cleanup old internal recordings (> 24h)
        val internalFolder = FileUtils.getPublicRecordingFolder(context)
        internalFolder.listFiles()?.forEach { file ->
            if (file.isFile && FileUtils.isAppRecording(context, file.absolutePath)) {
                FileUtils.deleteOldFile(file)
            }
        }
    }

    override suspend fun uploadPendingCallLogs(context: android.content.Context): Result<Unit> {
        if (!networkObserver.isConnected()) return Result.success(Unit)

        return try {
            syncMutex.withLock {
                var totalSuccess = 0
                var totalFailure = 0
                var hasMore = true

                while (hasMore) {
                    val pendingLogs = dao.getUnsyncedCallLogsBatch(SyncStatus.PENDING, BATCH_SIZE)
                    if (pendingLogs.isEmpty()) {
                        hasMore = false
                        break
                    }

                    Log.d(TAG, "Processing batch of ${pendingLogs.size} calls...")
                    
                    for (log in pendingLogs) {
                        val audioFile = log.recordingFilePath?.let { File(it) }
                        val result = uploadCallLog(log, audioFile)

                        if (result.isSuccess) {
                            totalSuccess++
                            // Delete internal recordings after successful upload
                            log.recordingFilePath?.let { path ->
                                if (FileUtils.isAppRecording(context, path)) {
                                    val file = File(path)
                                    if (file.exists()) {
                                        file.delete()
                                        Log.i(TAG, "Cleanup: Deleted uploaded file for ${log.clientNumber}")
                                    }
                                }
                            }
                        } else {
                            totalFailure++
                            // If a single upload fails, we mark it as failed or update retry count inside uploadCallLog
                            // We continue to next one in batch, but if it's a persistent network error, 
                            // maybe we should stop? For now, we continue the batch.
                        }
                    }
                    
                    // If the batch we just got was smaller than BATCH_SIZE, we've reached the end
                    if (pendingLogs.size < BATCH_SIZE) {
                        hasMore = false
                    }
                }

                Log.d(TAG, "Sync process finished. Total: $totalSuccess success, $totalFailure failure")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error in batch upload", e)
            Result.failure(e)
        }
    }
}
