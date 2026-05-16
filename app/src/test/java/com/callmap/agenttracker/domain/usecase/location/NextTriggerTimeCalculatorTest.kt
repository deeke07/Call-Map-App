package com.callmap.agenttracker.domain.usecase.location

import com.callmap.agenttracker.domain.model.RegistrationResult
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.*

class NextTriggerTimeCalculatorTest {

    private lateinit var shouldTrack: ShouldTrackLocationUseCase
    private lateinit var calculator: NextTriggerTimeCalculator

    @Before
    fun setup() {
        shouldTrack = ShouldTrackLocationUseCase()
        calculator = NextTriggerTimeCalculator(shouldTrack)
    }

    private fun createSettings(
        enabled: Boolean = true,
        days: List<String> = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday"),
        start: String = "09:00:00",
        end: String = "18:00:00"
    ) = RegistrationResult(
        deviceUuid = "uuid",
        deviceName = "name",
        recordingEnabled = true,
        trackingEnabled = enabled,
        locationFrequency = 60000,
        agentEmail = "email",
        agentName = "name",
        agentProfile = "profile",
        trackingDays = days,
        trackingStartTime = start,
        trackingEndTime = end
    )

    private fun getCalendarFor(dayOfWeek: Int, hour: Int, minute: Int = 0): Calendar {
        return Calendar.getInstance(Locale.US).apply {
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    @Test
    fun `when currently tracking, delay should be until end of today's window`() {
        // Monday, 10:00:00
        val now = getCalendarFor(Calendar.MONDAY, 10)
        val settings = createSettings() // 09:00 to 18:00
        
        val delay = calculator.calculateDelay(now.timeInMillis, settings)
        
        // Expected delay: 8 hours (until 18:00)
        val expectedDelay = 8L * 60 * 60 * 1000
        assertEquals(expectedDelay, delay)
    }

    @Test
    fun `when before tracking window today, delay should be until start of today's window`() {
        // Monday, 07:00:00
        val now = getCalendarFor(Calendar.MONDAY, 7)
        val settings = createSettings() // 09:00 to 18:00
        
        val delay = calculator.calculateDelay(now.timeInMillis, settings)
        
        // Expected delay: 2 hours (until 09:00)
        val expectedDelay = 2L * 60 * 60 * 1000
        assertEquals(expectedDelay, delay)
    }

    @Test
    fun `when after tracking window today, delay should be until start of next valid day's window`() {
        // Monday, 19:00:00
        val now = getCalendarFor(Calendar.MONDAY, 19)
        val settings = createSettings() // 09:00 to 18:00 on Mon-Fri
        
        val delay = calculator.calculateDelay(now.timeInMillis, settings)
        
        // Expected delay: 14 hours (until 09:00 next day - Tuesday)
        // 5 hours until midnight + 9 hours tomorrow
        val expectedDelay = (5L + 9L) * 60 * 60 * 1000
        assertEquals(expectedDelay, delay)
    }

    @Test
    fun `when on weekend and next tracking is Monday, delay should skip weekend`() {
        // Saturday, 10:00:00
        val now = getCalendarFor(Calendar.SATURDAY, 10)
        val settings = createSettings() // Mon-Fri, 09:00 to 18:00
        
        val delay = calculator.calculateDelay(now.timeInMillis, settings)
        
        // Saturday 10:00 to Monday 09:00
        // Sat: 14h left
        // Sun: 24h
        // Mon: 9h
        val expectedDelay = (14L + 24L + 9L) * 60 * 60 * 1000
        assertEquals(expectedDelay, delay)
    }

    @Test
    fun `when tracking disabled, return -1`() {
        val now = getCalendarFor(Calendar.MONDAY, 10)
        val settings = createSettings(enabled = false)
        
        val delay = calculator.calculateDelay(now.timeInMillis, settings)
        
        assertEquals(-1L, delay)
    }

    @Test
    fun `when no valid future window found, return -1`() {
        val now = getCalendarFor(Calendar.MONDAY, 10)
        val settings = createSettings(days = emptyList())
        
        val delay = calculator.calculateDelay(now.timeInMillis, settings)
        
        assertEquals(-1L, delay)
    }
}
