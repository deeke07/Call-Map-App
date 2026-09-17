package com.callmap.agenttracker.util

/**
 * Backend sends [location_frequency] in **seconds** only.
 * Supported range: 60 (1 min) … 1800 (30 min). Session storage is always milliseconds.
 */
object LocationFrequencyParser {

    const val MIN_INTERVAL_SECONDS = 60L
    const val MAX_INTERVAL_SECONDS = 30 * 60L
    const val DEFAULT_INTERVAL_SECONDS = 5 * 60L // 5 minutes

    const val MIN_INTERVAL_MS = MIN_INTERVAL_SECONDS * 1000L
    const val MAX_INTERVAL_MS = MAX_INTERVAL_SECONDS * 1000L

    /** API / registration: value is often seconds (e.g. 120 = 2 minutes) but logs show 120000. */
    fun fromApiSeconds(value: Long?): Long {
        if (value == null) return DEFAULT_INTERVAL_SECONDS * 1000L
        
        // Resilience: If backend sends milliseconds (e.g. 120000) instead of seconds (120)
        val ms = if (value >= 30_000L) {
            value // It's already milliseconds
        } else {
            value * 1000L // It's seconds
        }
        
        return ms.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    }

    /** Session DataStore value — normally ms; legacy entries may still be raw seconds. */
    fun fromStoredValue(stored: Long): Long {
        val ms = when {
            stored >= MIN_INTERVAL_MS -> stored // Milliseconds
            stored in MIN_INTERVAL_SECONDS..MAX_INTERVAL_SECONDS -> stored * 1000L // Seconds
            else -> DEFAULT_INTERVAL_SECONDS * 1000L
        }
        return ms.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    }
}
