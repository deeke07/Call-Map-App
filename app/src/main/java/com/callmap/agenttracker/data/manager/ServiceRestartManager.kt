package com.callmap.agenttracker.data.manager

import com.callmap.agenttracker.util.TrackingLog
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules recovery when [LocationService] is killed while tracking should be active.
 * Uses broadcast alarms (Doze-safe) rather than direct service PendingIntents.
 */
@Singleton
class ServiceRestartManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmScheduler: AlarmScheduler
) {

    companion object {
        private const val TAG = "ServiceRestartManager"
        private const val RESTART_DELAY_MS = 2 * 60 * 1000L
        private const val MAX_RETRY_DELAY_MS = 60 * 60 * 1000L
    }

    fun scheduleServiceRestart(delayMs: Long = RESTART_DELAY_MS) {
        try {
            alarmScheduler.scheduleServiceRecovery(delayMs)
            TrackingLog.d(TAG, "Service recovery in ${delayMs / 1000}s")
        } catch (e: Exception) {
            TrackingLog.e(TAG, "Error scheduling service restart", e)
        }
    }

    fun cancelServiceRestart() {
        alarmScheduler.cancelAllAlarms()
    }

    fun calculateBackoffDelay(attemptNumber: Int): Long {
        val exponentialDelay = RESTART_DELAY_MS * (1 shl (attemptNumber - 1).coerceAtLeast(0))
        return exponentialDelay.coerceAtMost(MAX_RETRY_DELAY_MS)
    }
}
