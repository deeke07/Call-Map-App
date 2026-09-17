package com.callmap.agenttracker.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WatchdogService"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service Connected - Acting as Watchdog")
        pokeServices()
    }

    private var lastPokeTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Triggered on UI changes. Useful for ensuring services are alive when user interacts with phone.
        val now = System.currentTimeMillis()
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || (now - lastPokeTime > 60_000L)) {
            lastPokeTime = now
            pokeServices()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service Interrupted")
    }

    private fun pokeServices() {
        // Start LocationService. It will check if it should be tracking inside its own logic.
        try {
            val intent = Intent(this, LocationService::class.java).apply {
                action = LocationService.ACTION_START
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog failed to start LocationService", e)
        }
    }
}
