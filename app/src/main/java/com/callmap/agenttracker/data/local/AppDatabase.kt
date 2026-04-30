package com.callmap.agenttracker.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.callmap.agenttracker.data.local.dao.CallLogDao
import com.callmap.agenttracker.data.local.dao.DeviceEventDao
import com.callmap.agenttracker.data.local.dao.LocationDao
import com.callmap.agenttracker.data.local.entity.CallLogEntity
import com.callmap.agenttracker.data.local.entity.DeviceEventEntity
import com.callmap.agenttracker.data.local.entity.LocationEntity

@Database(entities = [LocationEntity::class, CallLogEntity::class, DeviceEventEntity::class], version = 5, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun locationDao(): LocationDao
    abstract fun callLogDao(): CallLogDao
    abstract fun deviceEventDao(): DeviceEventDao

    companion object {
        const val DATABASE_NAME = "agent_tracker_db"
    }
}
