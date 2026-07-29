package com.callmap.agenttracker.util

import android.location.Location
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Validates location fixes before persisting — rejects stale or wildly inaccurate points
 * unless explicitly allowed as a desperate fallback.
 */
object LocationQualityGate {

    private const val TAG = "LocationQualityGate"
    private const val MAX_ACCURACY_METERS_NORMAL = 800f
    private const val MAX_ACCURACY_METERS_LENIENT = 2000f // Increased from 1500f
    private const val MAX_AGE_MS_NORMAL = 20 * 60 * 1000L
    private const val MAX_AGE_MS_LENIENT = 120 * 60 * 1000L // Increased from 90 min to 2 hours
    private const val MAX_AGE_MS_DESPERATE = 6 * 60 * 60 * 1000L // 6 hours for offline recovery

    data class Result(val accepted: Boolean, val reason: String)

    fun validate(
        location: Location,
        allowLenient: Boolean = false,
        isDesperate: Boolean = false
    ): Result {
        val maxAccuracy = if (allowLenient || isDesperate) MAX_ACCURACY_METERS_LENIENT else MAX_ACCURACY_METERS_NORMAL
        val maxAge = when {
            isDesperate -> MAX_AGE_MS_DESPERATE
            allowLenient -> MAX_AGE_MS_LENIENT
            else -> MAX_AGE_MS_NORMAL
        }

        if (!location.latitude.isFinite() || !location.longitude.isFinite()) {
            return Result(false, "invalid_coordinates")
        }
        if (location.latitude == 0.0 && location.longitude == 0.0) {
            return Result(false, "null_island")
        }

        // Use monotonic time for age calculation if available to avoid clock skew issues
        val ageMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        } else {
            System.currentTimeMillis() - location.time
        }

        if (ageMs > maxAge) {
            val res = Result(false, "stale_age_${ageMs / 1000}s")
            Log.w(TAG, "Location rejected: ${res.reason}")
            return res
        }

        if (location.hasAccuracy() && location.accuracy > maxAccuracy) {
            val res = Result(false, "poor_accuracy_${location.accuracy}m")
            Log.w(TAG, "Location rejected: ${res.reason}")
            return res
        }

        return Result(true, "ok")
    }
}
