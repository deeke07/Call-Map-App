package com.callmap.agenttracker.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.callmap.agenttracker.data.local.entity.SyncStatus

@Entity(tableName = "locations")
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val batteryLevel: Int?,
    val recordedAt: String,
    val syncStatus: SyncStatus = SyncStatus.PENDING
)
