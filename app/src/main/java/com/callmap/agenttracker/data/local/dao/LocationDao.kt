package com.callmap.agenttracker.data.local.dao

import androidx.room.*
import com.callmap.agenttracker.data.local.entity.LocationEntity
import com.callmap.agenttracker.data.local.entity.SyncStatus

@Dao
interface LocationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(location: LocationEntity)

    @Query("SELECT * FROM locations WHERE syncStatus IN ('PENDING', 'FAILED') ORDER BY recordedAt ASC LIMIT :limit")
    suspend fun getUnsyncedLocations(limit: Int = 500): List<LocationEntity>

    @Query("UPDATE locations SET syncStatus = :status WHERE id IN (:ids)")
    suspend fun updateSyncStatus(ids: List<Long>, status: SyncStatus)

    @Delete
    suspend fun deleteLocations(locations: List<LocationEntity>)

    @Query("DELETE FROM locations WHERE syncStatus = 'SYNCED'")
    suspend fun clearSyncedLocations()
}
