package com.callmap.agenttracker.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.callmap.agenttracker.data.local.entity.SyncStatus

@Entity(tableName = "call_logs")
data class CallLogEntity(
    @PrimaryKey val uniqueId: String, // deviceUuid + callStartedAt + callEndedAt + normalizedNumber
    val deviceUuid: String,
    val clientNumber: String,
    val callType: Int, // Use CallLog.Calls values (e.g., 1=INCOMING, 2=OUTGOING, 3=MISSED)
    val callDuration: Long,
    val callStartedAt: String,
    val callEndedAt: String,
    val recordingFilePath: String?,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val wasOnHold: Boolean = false,
    val interruptedNumbers: String? = null,
    val callAnsweredAt: String? = null,
    val callerName: String? = null,
    val durationMismatch: Boolean = false,
    val spotSettingVersion: String? = null,
    val apkVersion: String? = null,
    val latitude: String? = null,
    val longitude: String? = null,
    val batteryLevel: String? = null,
    val metaData: String? = null,
    val retryCount: Int = 0
)
