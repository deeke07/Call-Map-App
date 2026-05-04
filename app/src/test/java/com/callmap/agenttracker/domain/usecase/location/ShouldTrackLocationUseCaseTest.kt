package com.callmap.agenttracker.domain.usecase.location

import com.callmap.agenttracker.domain.model.RegistrationResult
import org.junit.Assert.*
import org.junit.Test
import java.util.*

class ShouldTrackLocationUseCaseTest {

    private val useCase = ShouldTrackLocationUseCase()

    private val baseSettings = RegistrationResult(
        deviceUuid = "uuid",
        deviceName = "test",
        recordingEnabled = true,
        trackingEnabled = true,
        locationFrequency = 300,
        agentEmail = "test@test.com",
        agentName = "Agent",
        agentProfile = "",
        trackingDays = listOf("monday", "tuesday", "wednesday", "thursday", "friday"),
        trackingStartTime = "09:00:00",
        trackingEndTime = "18:00:00"
    )

    @Test
    fun `return true when within day and time window`() {
        // Monday, 10:00 AM
        val calendar = Calendar.getInstance(Locale.US)
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        calendar.set(Calendar.HOUR_OF_DAY, 10)
        calendar.set(Calendar.MINUTE, 0)
        
        val result = useCase(calendar.timeInMillis, baseSettings)
        assertTrue(result)
    }

    @Test
    fun `return false when day is not in tracking days`() {
        // Sunday, 10:00 AM
        val calendar = Calendar.getInstance(Locale.US)
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        calendar.set(Calendar.HOUR_OF_DAY, 10)
        
        val result = useCase(calendar.timeInMillis, baseSettings)
        assertFalse(result)
    }

    @Test
    fun `return false when time is before start time`() {
        // Monday, 08:00 AM
        val calendar = Calendar.getInstance(Locale.US)
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        calendar.set(Calendar.HOUR_OF_DAY, 8)
        
        val result = useCase(calendar.timeInMillis, baseSettings)
        assertFalse(result)
    }

    @Test
    fun `return false when time is after end time`() {
        // Monday, 19:00 PM
        val calendar = Calendar.getInstance(Locale.US)
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        calendar.set(Calendar.HOUR_OF_DAY, 19)
        
        val result = useCase(calendar.timeInMillis, baseSettings)
        assertFalse(result)
    }

    @Test
    fun `handle overnight tracking correctly`() {
        val overnightSettings = baseSettings.copy(
            trackingStartTime = "22:00:00",
            trackingEndTime = "06:00:00"
        )
        
        // Monday, 23:00 PM
        val calendar = Calendar.getInstance(Locale.US)
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        assertTrue(useCase(calendar.timeInMillis, overnightSettings))
        
        // Monday, 05:00 AM
        calendar.set(Calendar.HOUR_OF_DAY, 5)
        assertTrue(useCase(calendar.timeInMillis, overnightSettings))
        
        // Monday, 12:00 PM
        calendar.set(Calendar.HOUR_OF_DAY, 12)
        assertFalse(useCase(calendar.timeInMillis, overnightSettings))
    }
}
