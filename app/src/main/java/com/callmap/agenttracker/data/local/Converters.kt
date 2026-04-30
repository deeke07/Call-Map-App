package com.callmap.agenttracker.data.local

import androidx.room.TypeConverter
import com.callmap.agenttracker.data.local.entity.SyncStatus

class Converters {
    @TypeConverter
    fun fromSyncStatus(status: SyncStatus): String {
        return status.name
    }

    @TypeConverter
    fun toSyncStatus(status: String): SyncStatus {
        return SyncStatus.valueOf(status)
    }
}
