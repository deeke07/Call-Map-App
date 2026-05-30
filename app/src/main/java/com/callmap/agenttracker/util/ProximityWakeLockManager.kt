package com.callmap.agenttracker.util

import android.content.Context
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages WakeLocks for critical operations during location tracking.
 * Ensures CPU remains awake during:
 * - BroadcastReceiver processing
 * - Service startup
 * - Location fetching
 * - Data sync
 *
 * Prevents "silent stops" where the CPU goes to sleep before work completes.
 */
@Singleton
class ProximityWakeLockManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var criticalOpsLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "ProximityWakeLockManager"
    }

    /**
     * Acquire a wake lock for critical operations.
     * @param durationMs How long to hold the lock (prevents runaway locks)
     * @param reason Human-readable description of why we need the lock
     */
    fun acquireCriticalLock(durationMs: Long = 30000L, reason: String = "critical_operation") {
        try {
            // Release previous lock if held
            if (criticalOpsLock?.isHeld == true) {
                criticalOpsLock?.release()
            }

            // PARTIAL_WAKE_LOCK: CPU stays awake, screen can sleep
            // This is what we want for background location tracking
            criticalOpsLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LocationTracking::Critical::$reason"
            )
            criticalOpsLock?.acquire(durationMs)
            Log.d(TAG, "Critical lock acquired (${durationMs / 1000}s): $reason")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring critical lock", e)
        }
    }

    /**
     * Release the critical wake lock.
     */
    fun releaseCriticalLock(reason: String = "operation_complete") {
        try {
            if (criticalOpsLock?.isHeld == true) {
                criticalOpsLock?.release()
                Log.d(TAG, "Critical lock released: $reason")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing critical lock", e)
        }
    }

    /**
     * Check if a lock is currently held.
     */
    fun isLocked(): Boolean = criticalOpsLock?.isHeld == true

    /**
     * Create a temporary lock for a specific duration.
     * Automatically releases after duration.
     */
    fun acquireTemporaryLock(durationMs: Long, reason: String): PowerManager.WakeLock {
        return powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationTracking::Temp::$reason").apply {
            try {
                acquire(durationMs)
                Log.d(TAG, "Temporary lock acquired (${durationMs / 1000}s): $reason")
            } catch (e: Exception) {
                Log.e(TAG, "Error acquiring temporary lock", e)
            }
        }
    }

    /**
     * Force release all held locks (cleanup).
     */
    fun releaseAll() {
        try {
            if (criticalOpsLock?.isHeld == true) {
                criticalOpsLock?.release()
                Log.d(TAG, "All wake locks released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing all locks", e)
        }
    }
}

