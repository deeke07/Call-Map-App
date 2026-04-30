package com.callmap.agenttracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.callmap.agenttracker.data.local.entity.*

@Dao
interface DeviceEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: DeviceEventEntity): Long

    @Query("SELECT * FROM device_events WHERE syncStatus = :status ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getUnsyncedEvents(status: SyncStatus, limit: Int): List<DeviceEventEntity>

    @Query("UPDATE device_events SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: Long, status: SyncStatus)

    @Query("UPDATE device_events SET retryCount = :count WHERE id = :id")
    suspend fun updateRetryCount(id: Long, count: Int)

    @Query("DELETE FROM device_events WHERE id = :id")
    suspend fun deleteEventById(id: Long)

    @Query("DELETE FROM device_events WHERE syncStatus = 'SYNCED'")
    suspend fun deleteSyncedEvents()

    @Query("DELETE FROM device_events WHERE syncStatus = 'SYNCED' AND createdAt < :timestamp")
    suspend fun cleanupOldEvents(timestamp: Long)

    @Query("SELECT COUNT(*) > 0 FROM device_events WHERE eventType = :type AND permissionName = :perm")
    suspend fun hasExistingEvent(type: String, perm: String?): Boolean

    @Query("SELECT COUNT(*) > 0 FROM device_events WHERE eventType = :type")
    suspend fun hasExistingEventType(type: String): Boolean

    @Query("DELETE FROM device_events WHERE eventType = :type AND permissionName = :perm")
    suspend fun deleteEventsByTypeAndPermission(type: String, perm: String?)

    @Query("DELETE FROM device_events WHERE eventType = :type")
    suspend fun deleteEventsByType(type: String)
}
