package com.callmap.agenttracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.callmap.agenttracker.data.manager.DeviceRestartDetector
import com.callmap.agenttracker.domain.manager.AppInitializer
import com.callmap.agenttracker.domain.manager.EventManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var eventManager: EventManager

    @Inject
    lateinit var appInitializer: AppInitializer

    @Inject
    lateinit var restartDetector: DeviceRestartDetector

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "Device boot completed detected")

            // Run in background to avoid ANR
            val scope = CoroutineScope(Dispatchers.IO)
            scope.launch {
                try {
                    // 1. Detect boot and check if we should resume tracking
                    val wasTracking = restartDetector.shouldResumeTrackingAfterRestart()

                    // 2. Log the restart event
                    eventManager.logEvent(
                        eventType = EventManager.DEVICE_RESTARTED,
                        metadata = mapOf(
                            "reason" to "system_boot",
                            "was_tracking" to wasTracking.toString()
                        )
                    )

                    // 3. Re-initialize app systems (services, workers)
                    appInitializer.init()

                    Log.i("BootReceiver", "Boot recovery complete. Was tracking: $wasTracking")
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Error during boot recovery", e)
                }
            }
        }
    }
}
