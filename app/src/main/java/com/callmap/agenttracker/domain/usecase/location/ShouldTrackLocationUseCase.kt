package com.callmap.agenttracker.domain.usecase.location

import android.util.Log
import com.callmap.agenttracker.domain.model.RegistrationResult
import java.util.*
import javax.inject.Inject

/**
 * Determines if location tracking should be active based on current time and backend settings.
 * Supports both same-day (e.g., 09:00 to 18:00) and overnight (e.g., 22:00 to 06:00) schedules.
 */
class ShouldTrackLocationUseCase @Inject constructor() {

    operator fun invoke(currentTimeMillis: Long, settings: RegistrationResult?): Boolean {
        if (settings == null || !settings.trackingEnabled) return false
        if (settings.trackingDays.isEmpty()) return false

        val calendar = Calendar.getInstance(Locale.US).apply { timeInMillis = currentTimeMillis }
        val allowedDays = settings.trackingDays.map { it.lowercase() }

        val startTimeMinutes = timeToMinutes(settings.trackingStartTime)
        val endTimeMinutes = timeToMinutes(settings.trackingEndTime)
        
        if (startTimeMinutes == -1 || endTimeMinutes == -1) {
            Log.w("ShouldTrackUseCase", "Invalid window strings: ${settings.trackingStartTime} - ${settings.trackingEndTime}")
            return false
        }

        val currentMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val isOvernight = startTimeMinutes > endTimeMinutes

        val isInWindow = if (!isOvernight) {
            currentMinutes in startTimeMinutes until endTimeMinutes
        } else {
            currentMinutes >= startTimeMinutes || currentMinutes < endTimeMinutes
        }

        if (!isInWindow) return false

        // Day-of-week logic for overnight shifts:
        // If we are in the morning part of an overnight window (e.g., 02:00), 
        // we check if the shift *started* on an allowed day (yesterday).
        val dayToCheck = if (isOvernight && currentMinutes < endTimeMinutes) {
            val yesterdayCal = (calendar.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
            yesterdayCal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.US)?.lowercase()
        } else {
            calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.US)?.lowercase()
        }

        val shouldTrack = allowedDays.contains(dayToCheck)

        if (!shouldTrack) {
            Log.d("ShouldTrackUseCase", "Outside allowed days: $dayToCheck not in $allowedDays")
        }

        return shouldTrack
    }

    private fun timeToMinutes(timeStr: String?): Int {
        if (timeStr.isNullOrEmpty()) return -1
        return try {
            val parts = timeStr.split(":")
            val hours = parts[0].toInt()
            val minutes = if (parts.size > 1) parts[1].toInt() else 0
            hours * 60 + minutes
        } catch (e: Exception) {
            Log.e("ShouldTrackUseCase", "Failed to parse time string: $timeStr")
            -1
        }
    }
}
