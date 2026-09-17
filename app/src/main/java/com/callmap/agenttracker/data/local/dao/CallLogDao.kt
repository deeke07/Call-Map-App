package com.callmap.agenttracker.data.local.dao

import androidx.room.*
import com.callmap.agenttracker.data.local.entity.CallLogEntity
import com.callmap.agenttracker.data.local.entity.SyncStatus

@Dao
interface CallLogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCallLog(callLog: CallLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun rememberCall(call: com.callmap.agenttracker.data.local.entity.CapturedCallEntity): Long

    @Transaction
    suspend fun saveCapturedCall(call: CallLogEntity): Long {
        if (rememberCall(com.callmap.agenttracker.data.local.entity.CapturedCallEntity(call.uniqueId)) == -1L) return -1
        return insertCallLog(call)
    }

    @Query("SELECT recordingFilePath FROM call_logs WHERE recordingFilePath IS NOT NULL AND syncStatus != 'SYNCED'")
    suspend fun getProtectedRecordingPaths(): List<String>

    @Query("SELECT * FROM call_logs WHERE syncStatus = :status ORDER BY createdAt ASC")
    suspend fun getUnsyncedCallLogs(status: SyncStatus = SyncStatus.PENDING): List<CallLogEntity>

    @Query("SELECT * FROM call_logs WHERE syncStatus IN ('PENDING', 'FAILED') ORDER BY (CASE WHEN syncStatus = 'PENDING' THEN 0 ELSE 1 END), createdAt ASC LIMIT :limit")
    suspend fun getPendingCallLogsBatch(limit: Int): List<CallLogEntity>

    @Query("SELECT * FROM call_logs WHERE syncStatus = :status ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getUnsyncedCallLogsBatch(status: SyncStatus, limit: Int): List<CallLogEntity>

    @Query("UPDATE call_logs SET syncStatus = :status WHERE uniqueId = :uniqueId")
    suspend fun updateSyncStatus(uniqueId: String, status: SyncStatus)

    @Query("SELECT EXISTS(SELECT 1 FROM captured_calls WHERE uniqueId = :uniqueId)")
    suspend fun exists(uniqueId: String): Boolean

    @Query("SELECT * FROM call_logs WHERE recordingFilePath IS NULL AND recordingAllowed = 1 AND syncStatus != 'SYNCED' AND callType IN (1, 2) ORDER BY createdAt DESC LIMIT 50")
    suspend fun getLogsMissingRecordings(): List<CallLogEntity>

    @Query("UPDATE call_logs SET recordingFilePath = :path WHERE uniqueId = :uniqueId")
    suspend fun updateRecordingPath(uniqueId: String, path: String)

    @Query("UPDATE call_logs SET retryCount = :retryCount WHERE uniqueId = :uniqueId")
    suspend fun updateRetryCount(uniqueId: String, retryCount: Int)

    @Delete
    suspend fun deleteCallLog(callLog: CallLogEntity)

    @Query("DELETE FROM call_logs WHERE syncStatus = 'SYNCED' AND createdAt < :timestamp")
    suspend fun clearOldSyncedLogs(timestamp: Long)
}
