package com.callmap.agenttracker.data.mapper

import android.provider.CallLog
import com.callmap.agenttracker.data.local.entity.CallLogEntity
import java.io.File

data class ApiRequestModel(
    val call_type: String,
    val call_duration: Long,
    val call_recording_file: String?,
    val call_answered_at: String?,
    val was_on_hold: String,
    val interrupted_numbers: String?
)

object CallMapper {
    fun mapCallToApiModel(callLog: CallLogEntity): ApiRequestModel {
        // A call is considered answered ONLY if it has a valid answered timestamp.
        val isAnswered = !callLog.callAnsweredAt.isNullOrEmpty()
        
        val fileExists = !callLog.recordingFilePath.isNullOrEmpty() && File(callLog.recordingFilePath).exists()

        val mappedType = when (callLog.callType) {
            CallLog.Calls.INCOMING_TYPE -> if (isAnswered) "INCOMING" else "NO_ANSWER"
            CallLog.Calls.OUTGOING_TYPE -> if (isAnswered) "OUTGOING" else "NO_ANSWER"
            CallLog.Calls.MISSED_TYPE -> "MISSED"
            CallLog.Calls.REJECTED_TYPE, 5 -> "REJECTED"
            6 -> "BLOCKED"
            else -> if (isAnswered) "INCOMING" else "NO_ANSWER"
        }

        return ApiRequestModel(
            call_type = mappedType,
            call_duration = callLog.callDuration,
            // Only send the recording file if the call was actually answered
            call_recording_file = if (isAnswered && fileExists) callLog.recordingFilePath else null,
            call_answered_at = callLog.callAnsweredAt,
            was_on_hold = if (callLog.wasOnHold) "1" else "0",
            interrupted_numbers = callLog.interruptedNumbers
        )
    }
}
