package com.callmap.agenttracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationFrequencyParserTest {

    @Test
    fun `api seconds match admin intervals`() {
        assertEquals(60_000L, LocationFrequencyParser.fromApiSeconds(60))
        assertEquals(120_000L, LocationFrequencyParser.fromApiSeconds(120))
        assertEquals(180_000L, LocationFrequencyParser.fromApiSeconds(180))
        assertEquals(300_000L, LocationFrequencyParser.fromApiSeconds(300))
        assertEquals(600_000L, LocationFrequencyParser.fromApiSeconds(600))
        assertEquals(900_000L, LocationFrequencyParser.fromApiSeconds(900))
        assertEquals(1_800_000L, LocationFrequencyParser.fromApiSeconds(1800))
    }

    @Test
    fun `api clamps out of range`() {
        assertEquals(60_000L, LocationFrequencyParser.fromApiSeconds(10))
        assertEquals(1_800_000L, LocationFrequencyParser.fromApiSeconds(9999))
    }

    @Test
    fun `stored milliseconds`() {
        assertEquals(120_000L, LocationFrequencyParser.fromStoredValue(120_000L))
    }

    @Test
    fun `stored legacy seconds`() {
        assertEquals(120_000L, LocationFrequencyParser.fromStoredValue(120))
    }
}
