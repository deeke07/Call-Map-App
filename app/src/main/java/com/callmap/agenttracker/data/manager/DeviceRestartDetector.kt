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
 *
 * Handles multiple boot scenarios:
 * - Normal boot (ACTION_BOOT_COMPLETED)
 * - Quick boot (QUICKBOOT_POWERON - OEM variant)
 * - Locked boot (ACTION_LOCKED_BOOT_COMPLETED)
 * - Direct crash recovery
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
        private const val KEY_LAST_BOOT_ACTION = "last_boot_action"
        private const val KEY_BOOT_COUNT = "boot_count"
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
            Log.i(TAG, "Device restart detected! Prev boot time: $lastBootTime, Current: $currentBootTime")
            incrementBootCount()
        }

        // Update boot time for next check
        prefs.edit().putLong(KEY_LAST_BOOT_TIME, currentBootTime).apply()

        return hasRestarted
    }

    /**
     * Record that the app was tracking before potential shutdown.
     * Call this in LocationService.onDestroy() if tracking should resume.
     */
    fun recordTrackingState(isTracking: Boolean) {
        prefs.edit().putBoolean(KEY_WAS_TRACKING, isTracking).apply()
        Log.d(TAG, "Tracking state recorded: $isTracking")
    }

    /**
     * Check if the app should resume tracking after restart.
     * This is called by BootReceiver to decide if we should start LocationService again.
     */
    fun shouldResumeTrackingAfterRestart(): Boolean {
        return prefs.getBoolean(KEY_WAS_TRACKING, false)
    }

    /**
     * Clear restart state (call after successfully resuming tracking).
     * Prevents multiple restart attempts.
     */
    fun clearRestartState() {
        prefs.edit().remove(KEY_WAS_TRACKING).apply()
        Log.d(TAG, "Restart state cleared")
    }

    /**
     * Record the boot action that triggered recovery.
     * Useful for diagnostics - different OEMs have different boot intents.
     */
    fun recordBootAction(action: String) {
        prefs.edit().putString(KEY_LAST_BOOT_ACTION, action).apply()
        Log.d(TAG, "Boot action recorded: $action")
    }

    /**
     * Get the last recorded boot action.
     */
    fun getLastBootAction(): String? {
        return prefs.getString(KEY_LAST_BOOT_ACTION, null)
    }

    /**
     * Increment boot counter for diagnostic purposes.
     * Can be used to detect devices that reboot frequently.
     */
    private fun incrementBootCount() {
        val current = prefs.getInt(KEY_BOOT_COUNT, 0)
        prefs.edit().putInt(KEY_BOOT_COUNT, current + 1).apply()
        Log.d(TAG, "Boot count: ${current + 1}")
    }

    /**
     * Get total number of boots detected by this app.
     */
    fun getBootCount(): Int {
        return prefs.getInt(KEY_BOOT_COUNT, 0)
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

