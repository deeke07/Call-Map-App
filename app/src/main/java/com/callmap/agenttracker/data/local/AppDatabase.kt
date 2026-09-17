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

@Database(entities = [LocationEntity::class, CallLogEntity::class, DeviceEventEntity::class, com.callmap.agenttracker.data.local.entity.CapturedCallEntity::class], version = 8, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun locationDao(): LocationDao
    abstract fun callLogDao(): CallLogDao
    abstract fun deviceEventDao(): DeviceEventDao

    companion object {
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE locations ADD COLUMN clientEventId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE locations SET clientEventId = lower(hex(randomblob(16)))")
                db.execSQL("ALTER TABLE device_events ADD COLUMN clientEventId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE device_events SET clientEventId = lower(hex(randomblob(16)))")
                db.execSQL("ALTER TABLE call_logs ADD COLUMN recordingAllowed INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE TABLE IF NOT EXISTS captured_calls (uniqueId TEXT NOT NULL PRIMARY KEY)")
                db.execSQL("INSERT OR IGNORE INTO captured_calls SELECT uniqueId FROM call_logs")
            }
        }
        const val DATABASE_NAME = "agent_tracker_db"
    }
}
