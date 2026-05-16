package com.callmap.agenttracker.domain.usecase.location

import com.callmap.agenttracker.domain.model.RegistrationResult
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

class NextTriggerTimeCalculator @Inject constructor(
    private val shouldTrack: ShouldTrackLocationUseCase
) {
    /**
     * Calculates the delay in milliseconds until the next "state change" (start or stop tracking).
     * Returns -1 if tracking is globally disabled or no valid future window is found.
     */
    fun calculateDelay(currentTimeMillis: Long, settings: RegistrationResult?): Long {
        if (settings == null || !settings.trackingEnabled || settings.trackingDays.isEmpty()) return -1
        
        val isCurrentlyTracking = shouldTrack(currentTimeMillis, settings)
        
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getDefault() }
        val startTimeStr = settings.trackingStartTime ?: return -1
        val endTimeStr = settings.trackingEndTime ?: return -1

        val calendar = Calendar.getInstance()
        calendar.timeInMillis = currentTimeMillis

        if (isCurrentlyTracking) {
            // Case 1: Currently tracking. We need to find when to STOP (the end of today's window).
            // Since we don't have overnight cases, the end time MUST be later today.
            val endParsed = try { sdf.parse(endTimeStr) } catch (e: Exception) { null } ?: return -1
            val endCal = Calendar.getInstance().apply {
                time = endParsed
                set(Calendar.YEAR, calendar.get(Calendar.YEAR))
                set(Calendar.MONTH, calendar.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, calendar.get(Calendar.DAY_OF_MONTH))
            }
            
            val delay = endCal.timeInMillis - currentTimeMillis
            return if (delay > 0) delay else -1
        } else {
            // Case 2: Not tracking. We need to find when the NEXT window starts.
            val startParsed = try { sdf.parse(startTimeStr) } catch (e: Exception) { null } ?: return -1
            val startCal = Calendar.getInstance().apply {
                time = startParsed
                set(Calendar.YEAR, calendar.get(Calendar.YEAR))
                set(Calendar.MONTH, calendar.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, calendar.get(Calendar.DAY_OF_MONTH))
            }

            // Check today and the next 7 days for the next allowed start time
            for (i in 0..7) {
                val candidateStart = (startCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, i) }
                
                // If it's today and the start time has already passed, it will be skipped by this check
                if (candidateStart.timeInMillis <= currentTimeMillis) continue

                // Check if this candidate day is in the allowed tracking days
                val dayName = candidateStart.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.US)?.lowercase()
                if (settings.trackingDays.any { it.lowercase() == dayName }) {
                    return candidateStart.timeInMillis - currentTimeMillis
                }
            }
        }

        return -1
    }
}
