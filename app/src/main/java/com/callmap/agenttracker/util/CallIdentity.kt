package com.callmap.agenttracker.util

import java.util.UUID

object CallIdentity {
    /** Shared by live capture and reconciliation; independent of duration/audio. */
    fun fromSystemLog(deviceUuid: String, rowId: Long, startedAt: Long): String =
        UUID.nameUUIDFromBytes("call:$deviceUuid:$rowId:$startedAt".toByteArray(Charsets.UTF_8)).toString()
}
