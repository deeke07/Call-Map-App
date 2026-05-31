package com.callmap.agenttracker.util

import android.location.Location

/**
 * Validates location fixes before persisting — rejects stale or wildly inaccurate points
 * unless explicitly allowed as a desperate fallback.
 */
object LocationQualityGate {

    private const val MAX_ACCURACY_METERS_NORMAL = 200f
    private const val MAX_ACCURACY_METERS_LENIENT = 500f
    private const val MAX_AGE_MS_NORMAL = 10 * 60 * 1000L
    private const val MAX_AGE_MS_LENIENT = 30 * 60 * 1000L

    data class Result(val accepted: Boolean, val reason: String)

    fun validate(
        location: Location,
        allowLenient: Boolean = false
    ): Result {
        val maxAccuracy = if (allowLenient) MAX_ACCURACY_METERS_LENIENT else MAX_ACCURACY_METERS_NORMAL
        val maxAge = if (allowLenient) MAX_AGE_MS_LENIENT else MAX_AGE_MS_NORMAL

        if (!location.latitude.isFinite() || !location.longitude.isFinite()) {
            return Result(false, "invalid_coordinates")
        }
        if (location.latitude == 0.0 && location.longitude == 0.0) {
            return Result(false, "null_island")
        }

        val ageMs = System.currentTimeMillis() - location.time
        if (ageMs > maxAge) {
            return Result(false, "stale_age_${ageMs / 1000}s")
        }

        if (location.hasAccuracy() && location.accuracy > maxAccuracy) {
            return Result(false, "poor_accuracy_${location.accuracy}m")
        }

        return Result(true, "ok")
    }
}
