package com.callmap.agenttracker.data.manager

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects device restarts and manages restart state for proper service recovery.
 * After a device restart, this ensures tracking resumes automatically.
 */
@Singleton
class DeviceRestartDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "device_restart_prefs",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val TAG = "DeviceRestartDetector"
        private const val KEY_LAST_BOOT_TIME = "last_boot_time"
        private const val KEY_APP_LAST_RUN = "app_last_run"
        private const val KEY_WAS_TRACKING = "was_tracking"
    }

    /**
     * Check if device has restarted since last app run.
     * Returns true if restart detected.
     */
    fun detectRestart(): Boolean {
        val currentBootTime = SystemClock.elapsedRealtime()
        val lastBootTime = prefs.getLong(KEY_LAST_BOOT_TIME, 0L)

        val hasRestarted = if (lastBootTime == 0L) {
            // First time checking
            false
        } else {
            // If current boot time is less than last boot time, device restarted
            // (or clock was reset, but we treat it as restart)
            currentBootTime < lastBootTime
        }

        if (hasRestarted) {
            Log.i(TAG, "Device restart detected!")
        }

        // Update boot time for next check
        prefs.edit().putLong(KEY_LAST_BOOT_TIME, currentBootTime).apply()

        return hasRestarted
    }

    /**
     * Record that the app was tracking before potential shutdown.
     */
    fun recordTrackingState(isTracking: Boolean) {
        prefs.edit().putBoolean(KEY_WAS_TRACKING, isTracking).apply()
    }

    /**
     * Check if the app should resume tracking after restart.
     */
    fun shouldResumeTrackingAfterRestart(): Boolean {
        return prefs.getBoolean(KEY_WAS_TRACKING, false)
    }

    /**
     * Clear restart state (call after successfully resuming tracking).
     */
    fun clearRestartState() {
        prefs.edit().remove(KEY_WAS_TRACKING).apply()
    }

    /**
     * Get time since last successful app run.
     */
    fun getTimeSinceLastRun(): Long {
        val lastRun = prefs.getLong(KEY_APP_LAST_RUN, System.currentTimeMillis())
        val timeSince = System.currentTimeMillis() - lastRun

        // Update last run time
        prefs.edit().putLong(KEY_APP_LAST_RUN, System.currentTimeMillis()).apply()

        return timeSince
    }
}

