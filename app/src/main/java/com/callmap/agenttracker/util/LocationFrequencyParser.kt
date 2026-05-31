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

    /** API / registration: value is seconds (e.g. 120 = 2 minutes). */
    fun fromApiSeconds(seconds: Long?): Long {
        val sec = (seconds ?: DEFAULT_INTERVAL_SECONDS).coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)
        return sec * 1000L
    }

    /** Session DataStore value — normally ms; legacy entries may still be raw seconds. */
    fun fromStoredValue(stored: Long): Long {
        val ms = when {
            stored in MIN_INTERVAL_MS..MAX_INTERVAL_MS -> stored
            stored in MIN_INTERVAL_SECONDS..MAX_INTERVAL_SECONDS -> stored * 1000L
            else -> DEFAULT_INTERVAL_SECONDS * 1000L
        }
        return ms.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    }
}
