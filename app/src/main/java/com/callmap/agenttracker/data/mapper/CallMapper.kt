package com.callmap.agenttracker.data.mapper

import android.provider.CallLog
import com.callmap.agenttracker.data.local.entity.CallLogEntity
import java.io.File

data class ApiRequestModel(
    val call_type: String,
    val call_duration: Long,
    val call_recording_file: String?,
    val call_answered_at: String?
)

object CallMapper {
    fun mapCallToApiModel(callLog: CallLogEntity): ApiRequestModel {
        val hasAnsweredTimestamp = !callLog.callAnsweredAt.isNullOrEmpty()
        val hasDuration = callLog.callDuration > 0
        val fileExists = !callLog.recordingFilePath.isNullOrEmpty() && File(callLog.recordingFilePath).exists()

        val isEffectivelyAnswered = hasAnsweredTimestamp && hasDuration && fileExists

        var mappedType = when (callLog.callType) {
            CallLog.Calls.INCOMING_TYPE -> if (isEffectivelyAnswered) "INCOMING" else "NO_ANSWER"
            CallLog.Calls.OUTGOING_TYPE -> if (isEffectivelyAnswered) "OUTGOING" else "NO_ANSWER"
            CallLog.Calls.MISSED_TYPE -> "MISSED"
            CallLog.Calls.REJECTED_TYPE -> "REJECTED"
            5 -> "REJECTED"
            6 -> "REJECTED"
            else -> if (isEffectivelyAnswered) "INCOMING" else "NO_ANSWER"
        }

        val finalDuration: Long
        val finalRecording: String?
        val finalAnsweredAt: String?

        if (isEffectivelyAnswered) {
            finalDuration = callLog.callDuration
            finalRecording = callLog.recordingFilePath
            finalAnsweredAt = callLog.callAnsweredAt
        } else {
            finalDuration = 0L
            finalRecording = null
            finalAnsweredAt = null
            
            if (mappedType == "INCOMING" || mappedType == "OUTGOING") {
                mappedType = "NO_ANSWER"
            }
        }

        return ApiRequestModel(
            call_type = mappedType,
            call_duration = finalDuration,
            call_recording_file = finalRecording,
            call_answered_at = finalAnsweredAt
        )
    }
}
