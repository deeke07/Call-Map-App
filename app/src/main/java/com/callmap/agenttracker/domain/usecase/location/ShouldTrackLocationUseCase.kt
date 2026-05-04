package com.callmap.agenttracker.domain.usecase.location

import com.callmap.agenttracker.domain.model.RegistrationResult
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

class ShouldTrackLocationUseCase @Inject constructor() {

    operator fun invoke(currentTimeMillis: Long, settings: RegistrationResult?): Boolean {
        if (settings == null) return false
        if (!settings.trackingEnabled) return false
        
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = currentTimeMillis
        
        // 1. Check Day
        val currentDay = calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.US)?.lowercase()
        if (settings.trackingDays.isEmpty() || !settings.trackingDays.map { it.lowercase() }.contains(currentDay)) {
            return false
        }

        // 2. Check Time
        val startTimeStr = settings.trackingStartTime ?: return false
        val endTimeStr = settings.trackingEndTime ?: return false

        return try {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
            val startTime = sdf.parse(startTimeStr) ?: return false
            val endTime = sdf.parse(endTimeStr) ?: return false
            
            // Current time in HH:mm:ss format for comparison
            val currentTimeStr = sdf.format(Date(currentTimeMillis))
            val currentTime = sdf.parse(currentTimeStr) ?: return false

            if (startTime.before(endTime)) {
                // Normal case: e.g., 09:00 to 18:00
                (currentTime.after(startTime) || currentTime == startTime) && 
                (currentTime.before(endTime) || currentTime == endTime)
            } else {
                // Overnight case: e.g., 22:00 to 06:00
                (currentTime.after(startTime) || currentTime == startTime) || 
                (currentTime.before(endTime) || currentTime == endTime)
            }
        } catch (e: Exception) {
            false
        }
    }
}
