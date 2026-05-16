package com.callmap.agenttracker.data.local.dao

import androidx.room.*
import com.callmap.agenttracker.data.local.entity.CallLogEntity
import com.callmap.agenttracker.data.local.entity.SyncStatus

@Dao
interface CallLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallLog(callLog: CallLogEntity): Long

    @Query("SELECT * FROM call_logs WHERE syncStatus = :status ORDER BY createdAt ASC")
    suspend fun getUnsyncedCallLogs(status: SyncStatus = SyncStatus.PENDING): List<CallLogEntity>

    @Query("SELECT * FROM call_logs WHERE syncStatus IN ('PENDING', 'FAILED') ORDER BY (CASE WHEN syncStatus = 'PENDING' THEN 0 ELSE 1 END), createdAt ASC LIMIT :limit")
    suspend fun getPendingCallLogsBatch(limit: Int): List<CallLogEntity>

    @Query("SELECT * FROM call_logs WHERE syncStatus = :status ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getUnsyncedCallLogsBatch(status: SyncStatus, limit: Int): List<CallLogEntity>

    @Query("UPDATE call_logs SET syncStatus = :status WHERE uniqueId = :uniqueId")
    suspend fun updateSyncStatus(uniqueId: String, status: SyncStatus)

    @Query("SELECT EXISTS(SELECT 1 FROM call_logs WHERE uniqueId = :uniqueId)")
    suspend fun exists(uniqueId: String): Boolean

    @Query("SELECT * FROM call_logs WHERE recordingFilePath IS NULL AND callType NOT IN (3, 4, 5, 6) ORDER BY createdAt DESC LIMIT 50")
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