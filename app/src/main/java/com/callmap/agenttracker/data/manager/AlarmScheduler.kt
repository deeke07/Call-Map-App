package com.callmap.agenttracker.data.manager

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.callmap.agenttracker.receiver.ScheduleReceiver
import com.callmap.agenttracker.util.AlarmOptimizer
import com.callmap.agenttracker.util.TrackingLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmOptimizer: AlarmOptimizer
) {
    companion object {
        private const val TAG = "AlarmScheduler"
        const val ALARM_REQUEST_CODE_LOCATION = 1001
        const val ALARM_REQUEST_CODE_TRANSITION = 1002
        private const val PREFS_NAME = "location_alarm_prefs"
        private const val KEY_NEXT_LOCATION_WAKE_AT = "next_location_wake_at_ms"
        private const val KEY_LAST_INTERVAL_MS = "last_interval_ms"
    }

    @Volatile
    private var nextLocationWakeAtMs: Long = 0L

    private val prefs by lazy {
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Schedules the next tracking-window transition (start/stop) via audit worker.
     */
    fun scheduleExactAlarm(triggerAtMillis: Long) {
        if (triggerAtMillis <= System.currentTimeMillis()) return
        val pendingIntent = transitionPendingIntent()
        TrackingLog.i(TAG, "Transition alarm at ${java.util.Date(triggerAtMillis)}")
        alarmOptimizer.scheduleDozeAwareAlarm(triggerAtMillis, pendingIntent, "schedule_transition")
    }

    /**
     * Next location wake (ephemeral FGS cycle). Replaces any prior location wake alarm.
     */
    fun scheduleWatchdogAlarm(delayMs: Long) {
        val safeDelay = delayMs.coerceAtLeast(5_000L)
        val triggerAt = System.currentTimeMillis() + safeDelay
        recordLocationWake(triggerAt, safeDelay)
        val pendingIntent = locationWakePendingIntent()
        TrackingLog.d(TAG, "Next location wake in ${safeDelay / 1000}s")
        alarmOptimizer.scheduleDozeAwareAlarm(triggerAt, pendingIntent, "location_wake")
    }

    /**
     * Recovery after unexpected service death — same delivery path as location wake.
     */
    fun scheduleServiceRecovery(delayMs: Long) {
        if (hasUpcomingLocationWake()) {
            TrackingLog.d(TAG, "Recovery skipped — location wake already scheduled")
            return
        }
        scheduleWatchdogAlarm(delayMs)
    }

    /**
     * True when a location alarm is still pending (ephemeral gap between FGS cycles).
     */
    fun hasUpcomingLocationWake(graceMs: Long = 10_000L): Boolean {
        val next = maxOf(nextLocationWakeAtMs, prefs.getLong(KEY_NEXT_LOCATION_WAKE_AT, 0L))
        if (next <= 0L) return false
        val remaining = next - System.currentTimeMillis()
        return remaining > graceMs
    }

    fun lastScheduledIntervalMs(): Long =
        prefs.getLong(KEY_LAST_INTERVAL_MS, 0L).takeIf { it > 0L }
            ?: 120_000L

    fun clearLocationWakeSchedule() {
        nextLocationWakeAtMs = 0L
        prefs.edit().remove(KEY_NEXT_LOCATION_WAKE_AT).apply()
    }

    fun cancelAllAlarms() {
        listOf(
            transitionPendingIntent(),
            locationWakePendingIntent()
        ).forEach { pi ->
            alarmOptimizer.cancelAlarm(pi, "cancel_all")
            pi.cancel()
        }
        clearLocationWakeSchedule()
    }

    private fun recordLocationWake(triggerAtMs: Long, intervalMs: Long) {
        nextLocationWakeAtMs = triggerAtMs
        prefs.edit()
            .putLong(KEY_NEXT_LOCATION_WAKE_AT, triggerAtMs)
            .putLong(KEY_LAST_INTERVAL_MS, intervalMs)
            .apply()
    }

    private fun transitionPendingIntent(): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ScheduleReceiver.ACTION_SCHEDULE_AUDIT
        }
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE_TRANSITION,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun locationWakePendingIntent(): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ScheduleReceiver.ACTION_LOCATION_ALARM
        }
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE_LOCATION,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
