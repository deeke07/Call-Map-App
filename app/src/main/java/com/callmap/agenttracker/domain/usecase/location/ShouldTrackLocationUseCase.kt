package com.callmap.agenttracker.domain.usecase.location

import android.util.Log
import com.callmap.agenttracker.domain.model.RegistrationResult
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

/**
 * Determines if location tracking should be active based on current time and backend settings.
 * Only supports same-day (normal) schedules (e.g., 09:00:00 to 18:00:00).
 */
class ShouldTrackLocationUseCase @Inject constructor() {

    operator fun invoke(currentTimeMillis: Long, settings: RegistrationResult?): Boolean {
        if (settings == null || !settings.trackingEnabled) return false
        if (settings.trackingDays.isEmpty()) return false

        val calendar = Calendar.getInstance(Locale.US).apply { timeInMillis = currentTimeMillis }

        // 1. Check Day (Lowercase comparison for robustness)
        val currentDay = calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.US)?.lowercase()
        val allowedDays = settings.trackingDays.map { it.lowercase() }
        if (!allowedDays.contains(currentDay)) return false

        // 2. Check Time Window
        val startTimeStr = settings.trackingStartTime ?: return false
        val endTimeStr = settings.trackingEndTime ?: return false

        return try {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getDefault() }
            val startTime = sdf.parse(startTimeStr) ?: return false
            val endTime = sdf.parse(endTimeStr) ?: return false
            
            // Current time formatted and parsed to remove date components for pure time comparison
            val currentTime = sdf.parse(sdf.format(Date(currentTimeMillis))) ?: return false

            // Strict same-day window: [startTime, endTime)
            // We use exclusive end time so that exactly at 18:00:00, tracking stops.
            val shouldTrack = (currentTime == startTime || currentTime.after(startTime)) && currentTime.before(endTime)
            
            if (!shouldTrack && currentTime.after(startTime)) {
                Log.d("ShouldTrackUseCase", "Tracking window ended: $endTimeStr. Current: ${sdf.format(currentTime)}")
            }
            
            shouldTrack
        } catch (e: Exception) {
            false
        }
    }
}
