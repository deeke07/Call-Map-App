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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    companion object {
        private const val TAG = "BootReceiver"
    }

    // FIXED Bug 6: scope is leaked — store reference and cancel after work completes
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {

            Log.i(TAG, "Boot detected: ${intent.action}")

            // FIXED: use goAsync() to get a PendingResult — keeps receiver alive for async work
            val pendingResult = goAsync()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            scope.launch {
                try {
                    val wasTracking = restartDetector.shouldResumeTrackingAfterRestart()

                    try {
                        eventManager.logEvent(
                            eventType = EventManager.DEVICE_RESTARTED,
                            metadata = mapOf(
                                "reason" to "system_boot",
                                "was_tracking" to wasTracking.toString(),
                                "boot_action" to (intent.action ?: "unknown")
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error logging boot event", e)
                    }

                    try {
                        appInitializer.init()
                        Log.i(TAG, "App init complete after boot")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error initializing app after boot", e)
                    }

                    try {
                        restartDetector.clearRestartState()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error clearing restart state", e)
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Critical error during boot recovery", e)
                    try { appInitializer.init() } catch (e2: Exception) {
                        Log.e(TAG, "Failed to initialize app after error recovery", e2)
                    }
                } finally {
                    // FIXED Bug 6: finish the async result and cancel scope to prevent leak
                    pendingResult.finish()
                    scope.cancel()
                }
            }
        }
    }
}
