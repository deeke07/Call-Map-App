package com.callmap.agenttracker.domain.repository

import com.callmap.agenttracker.data.local.entity.CallLogEntity
import java.io.File

interface CallRepository {
    suspend fun saveCallLog(callLog: CallLogEntity): Long
    suspend fun exists(uniqueId: String): Boolean
    suspend fun uploadCallLog(callLog: CallLogEntity, audioFile: File?): Result<Unit>
    suspend fun recoverOrphanFiles(context: android.content.Context)
    suspend fun uploadPendingCallLogs(context: android.content.Context): Result<Unit>
}
