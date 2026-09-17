package com.callmap.agenttracker.data

import com.callmap.agenttracker.data.local.entity.CallLogEntity
import com.callmap.agenttracker.data.mapper.CallMapper
import org.junit.Assert.*
import org.junit.Test

class CallMapperTest {
    private fun call(type: Int = 2) = CallLogEntity(
        uniqueId = "test", deviceUuid = "device", clientNumber = "123", callType = type,
        callDuration = 73, callStartedAt = "2026-09-17 12:00:00", callEndedAt = "2026-09-17 12:01:13",
        callAnsweredAt = "2026-09-17 12:00:00", recordingFilePath = null, recordingAllowed = false
    )
    @Test fun metadataWithoutAudioRetainsAnsweredTypeAndDuration() {
        val result = CallMapper.mapCallToApiModel(call())
        assertEquals("OUTGOING", result.call_type)
        assertEquals(73L, result.call_duration)
        assertNull(result.call_recording_file)
    }
    @Test fun blockedCallUsesBackendSupportedType() {
        assertEquals("BLOCKED", CallMapper.mapCallToApiModel(call(6)).call_type)
    }
    @Test fun disabledRecordingNeverAttachesAnExistingFile() {
        val file = kotlin.io.path.createTempFile("call-mapper-test", ".wav").toFile()
        try {
            assertNull(CallMapper.mapCallToApiModel(call().copy(recordingFilePath = file.absolutePath)).call_recording_file)
        } finally { file.delete() }
    }
}
