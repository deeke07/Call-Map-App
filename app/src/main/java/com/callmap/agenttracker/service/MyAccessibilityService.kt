package com.callmap.agenttracker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.os.SystemClock
import android.os.UserManager
import com.callmap.agenttracker.domain.manager.ServiceManager
import javax.inject.Inject
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WatchdogService"
        @Volatile
        var isConnected: Boolean = false
            private set
    }

    @Inject lateinit var serviceManager: ServiceManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
        Log.d(TAG, "Accessibility Service Connected - Acting as Watchdog")
        pokeServices()
    }

    private var lastPokeTime: Long? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Triggered on UI changes. Useful for ensuring services are alive when user interacts with phone.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            pokeServices()
        }
    }

    override fun onInterrupt() {
        // This callback interrupts feedback, not the connection or the user's permission.
        Log.w(TAG, "Accessibility Service Interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isConnected = false
        Log.i(TAG, "Accessibility disconnected; Android manages reconnection")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isConnected = false
        super.onDestroy()
    }

    private fun pokeServices() {
        if (getSystemService(UserManager::class.java)?.isUserUnlocked != true) return
        val now = SystemClock.elapsedRealtime()
        if (lastPokeTime?.let { now - it < 60_000L } == true) return
        lastPokeTime = now
        // Use the existing config-aware recovery path, once per minute at most.
        try {
            serviceManager.runWatchdogCheck()
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog failed to start LocationService", e)
        }
    }
}
