package com.callmap.agenttracker.domain.usecase.location

import com.callmap.agenttracker.domain.model.RegistrationResult
import java.util.*
import javax.inject.Inject

/**
 * Calculates the delay in milliseconds until the next tracking state change (Start or Stop).
 * Supports overnight windows (e.g., 22:00 to 06:00).
 */
class NextTriggerTimeCalculator @Inject constructor(
    private val shouldTrack: ShouldTrackLocationUseCase
) {
    fun calculateDelay(currentTimeMillis: Long, settings: RegistrationResult?): Long {
        if (settings == null || !settings.trackingEnabled || settings.trackingDays.isEmpty()) return -1
        
        val isCurrentlyTracking = shouldTrack(currentTimeMillis, settings)
        val startTimeMinutes = timeToMinutes(settings.trackingStartTime)
        val endTimeMinutes = timeToMinutes(settings.trackingEndTime)

        if (startTimeMinutes == -1 || endTimeMinutes == -1) return -1

        val calendar = Calendar.getInstance()
        calendar.timeInMillis = currentTimeMillis
        val currentMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

        return if (isCurrentlyTracking) {
            // Find when to STOP
            val minutesUntilStop = if (startTimeMinutes <= endTimeMinutes) {
                // Same-day window (e.g., 09:00 - 18:00)
                endTimeMinutes - currentMinutes
            } else {
                // Overnight window (e.g., 22:00 - 06:00)
                if (currentMinutes >= startTimeMinutes) {
                    // We are in the late-night part (e.g., 23:00)
                    (1440 - currentMinutes) + endTimeMinutes
                } else {
                    // We are in the early-morning part (e.g., 02:00)
                    endTimeMinutes - currentMinutes
                }
            }
            // Add a 1-minute buffer to ensure we are clearly past the boundary
            (minutesUntilStop.toLong() * 60_000L) + 30_000L
        } else {
            // Find when to START (Check next 7 days)
            for (i in 0..7) {
                val candidateCal = (calendar.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, i) }
                val dayName = candidateCal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.US)?.lowercase()
                
                if (settings.trackingDays.any { it.lowercase() == dayName }) {
                    val candidateStartMillis = candidateCal.apply {
                        set(Calendar.HOUR_OF_DAY, startTimeMinutes / 60)
                        set(Calendar.MINUTE, startTimeMinutes % 60)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis

                    if (candidateStartMillis > currentTimeMillis) {
                        return candidateStartMillis - currentTimeMillis
                    }
                }
            }
            -1
        }
    }

    private fun timeToMinutes(timeStr: String?): Int {
        if (timeStr.isNullOrEmpty()) return -1
        return try {
            val parts = timeStr.split(":")
            parts[0].toInt() * 60 + (if (parts.size > 1) parts[1].toInt() else 0)
        } catch (e: Exception) { -1 }
    }
}
