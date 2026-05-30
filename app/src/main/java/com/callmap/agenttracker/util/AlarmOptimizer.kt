package com.callmap.agenttracker.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optimizes AlarmManager scheduling for Doze and battery saver modes.
 *
 * Issues this fixes:
 * - AlarmManager.set() gets batched in Doze mode → triggers hours late
 * - setAndAllowWhileIdle() sometimes fails silently → no wake
 * - Exact alarms get throttled → location stops
 *
 * Solution:
 * 1. Always use setExactAndAllowWhileIdle when possible
 * 2. Fall back to setAndAllowWhileIdle if exact not available
 * 3. Log all scheduling for diagnostics
 * 4. Handle permission errors gracefully
 *
 * Available for injection via AppModule.provideAlarmOptimizer()
 * Can be used by services and receivers that schedule alarms.
 */
@Singleton
class AlarmOptimizer @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    companion object {
        private const val TAG = "AlarmOptimizer"
    }

    /**
     * Schedule an alarm that respects Doze mode.
     * This is what AlarmManager should do by default, but doesn't.
     */
    fun scheduleDozeAwareAlarm(
        triggerAtMs: Long,
        pendingIntent: PendingIntent,
        operation: String = "location_tracking"
    ): Boolean {
        if (alarmManager == null) {
            Log.e(TAG, "AlarmManager not available")
            return false
        }

        return try {
            val delayMs = triggerAtMs - System.currentTimeMillis()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+: Check if we can schedule exact alarms
                if (alarmManager.canScheduleExactAlarms()) {
                    try {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMs,
                            pendingIntent
                        )
                        Log.d(TAG, "✓ Alarm scheduled EXACT+DOZE (${delayMs / 1000}s): $operation")
                        true
                    } catch (e: SecurityException) {
                        Log.w(TAG, "SCHEDULE_EXACT_ALARM permission denied. Falling back to inexact.")
                        scheduleInexactDozeAlarm(triggerAtMs, pendingIntent, operation)
                    }
                } else {
                    // Permission granted but quota exhausted - use inexact
                    Log.w(TAG, "Exact alarm quota exhausted. Using inexact (still Doze-safe).")
                    scheduleInexactDozeAlarm(triggerAtMs, pendingIntent, operation)
                }
            } else {
                // Pre-Android 12: Always try exact first
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMs,
                        pendingIntent
                    )
                    Log.d(TAG, "✓ Alarm scheduled EXACT+DOZE (${delayMs / 1000}s): $operation")
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "Exact alarm failed (${e.message}). Using inexact.")
                    scheduleInexactDozeAlarm(triggerAtMs, pendingIntent, operation)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical error scheduling alarm", e)
            false
        }
    }

    /**
     * Schedule an inexact alarm that still pierces Doze mode.
     * Not as reliable as exact, but better than normal set().
     */
    private fun scheduleInexactDozeAlarm(
        triggerAtMs: Long,
        pendingIntent: PendingIntent,
        operation: String
    ): Boolean {
        return try {
            val delayMs = triggerAtMs - System.currentTimeMillis()
            alarmManager?.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMs,
                pendingIntent
            )
            Log.d(TAG, "✓ Alarm scheduled INEXACT+DOZE (${delayMs / 1000}s): $operation")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling inexact doze alarm", e)
            false
        }
    }

    /**
     * Cancel a previously scheduled alarm.
     */
    fun cancelAlarm(pendingIntent: PendingIntent, operation: String = "unknown"): Boolean {
        return try {
            alarmManager?.cancel(pendingIntent)
            Log.d(TAG, "✓ Alarm cancelled: $operation")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling alarm: $operation", e)
            false
        }
    }

    /**
     * Check if exact alarm scheduling is available on this device.
     */
    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() ?: false
        } else {
            true // Pre-Android 12 can always use exact
        }
    }

    /**
     * Check if app has permission to schedule alarms.
     */
    fun hasScheduleExactAlarmPermission(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                @Suppress("InlinedApi")
                val permission = android.Manifest.permission.SCHEDULE_EXACT_ALARM
                val result = androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    permission
                )
                result == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                true // Pre-Android 12 doesn't have this permission
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Calculate the next alarm time with jitter to avoid thundering herd.
     * Useful when multiple alarms would trigger at the same time.
     */
    fun calculateNextAlarmTime(baseIntervalMs: Long, jitterMs: Long = 0L): Long {
        val jitter = if (jitterMs > 0) (Math.random() * jitterMs).toLong() else 0L
        return System.currentTimeMillis() + baseIntervalMs + jitter
    }
}



