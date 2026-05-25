package com.callmap.agenttracker.data.manager

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.callmap.agenttracker.service.LocationService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages automatic restart of location tracking service.
 * Detects when service is killed and schedules restart with exponential backoff.
 */
@Singleton
class ServiceRestartManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    companion object {
        private const val TAG = "ServiceRestartManager"
        private const val RESTART_DELAY_MS = 5 * 60 * 1000L // 5 minutes initial delay
        private const val MAX_RETRY_DELAY_MS = 60 * 60 * 1000L // 1 hour max delay
    }

    /**
     * Schedule a restart of the location service.
     * Used when service is detected to have crashed or been killed.
     */
    fun scheduleServiceRestart(delayMs: Long = RESTART_DELAY_MS) {
        if (alarmManager == null) {
            Log.e(TAG, "AlarmManager not available")
            return
        }

        try {
            val intent = Intent(context, LocationService::class.java).apply {
                action = LocationService.ACTION_START
            }

            val pendingIntent = PendingIntent.getService(
                context,
                12345,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerAt = System.currentTimeMillis() + delayMs

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                    Log.d(TAG, "Service restart scheduled in ${delayMs / 1000}s")
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                    Log.w(TAG, "Service restart scheduled (inexact) in ${delayMs / 1000}s")
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
                Log.d(TAG, "Service restart scheduled in ${delayMs / 1000}s")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling service restart", e)
        }
    }

    /**
     * Cancel any pending service restart alarms.
     */
    fun cancelServiceRestart() {
        try {
            val intent = Intent(context, LocationService::class.java).apply {
                action = LocationService.ACTION_START
            }

            val pendingIntent = PendingIntent.getService(
                context,
                12345,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )

            if (pendingIntent != null) {
                alarmManager?.cancel(pendingIntent)
                Log.d(TAG, "Service restart alarm cancelled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling service restart", e)
        }
    }

    /**
     * Calculate exponential backoff delay for retry.
     * Useful for progressive retry delays if initial restart fails.
     */
    fun calculateBackoffDelay(attemptNumber: Int): Long {
        val baseDelay = RESTART_DELAY_MS
        val exponentialDelay = baseDelay * (1 shl (attemptNumber - 1))
        return exponentialDelay.coerceAtMost(MAX_RETRY_DELAY_MS)
    }
}

