package com.callmap.agenttracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import com.callmap.agenttracker.data.manager.DeviceRestartDetector
import com.callmap.agenttracker.domain.manager.AppInitializer
import com.callmap.agenttracker.domain.manager.EventManager
import dagger.Lazy
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
    lateinit var eventManager: Lazy<EventManager>

    @Inject
    lateinit var appInitializer: Lazy<AppInitializer>

    @Inject
    lateinit var restartDetector: Lazy<DeviceRestartDetector>

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_USER_PRESENT ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED) {

            Log.i(TAG, "Boot, unlock or update detected: $action")

            // DeviceRestartDetector uses DeviceProtectedStorage, so it's safe to use even if locked.
            try {
                restartDetector.get().recordBootAction(action)
            } catch (e: Exception) {
                Log.e(TAG, "Error recording boot action in protected storage", e)
            }

            val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
            if (!userManager.isUserUnlocked) {
                Log.w(TAG, "User is locked. Deferring initialization until unlock.")
                return
            }

            // FIXED: use goAsync() to get a PendingResult — keeps receiver alive for async work
            val pendingResult = goAsync()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            scope.launch {
                try {
                    val wasTracking = restartDetector.get().shouldResumeTrackingAfterRestart()

                    try {
                        eventManager.get().logEvent(
                            eventType = EventManager.DEVICE_RESTARTED,
                            metadata = mapOf(
                                "reason" to "system_boot",
                                "was_tracking" to wasTracking.toString(),
                                "boot_action" to action
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error logging boot event", e)
                    }

                    try {
                        appInitializer.get().init()
                        Log.i(TAG, "App init complete after boot")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error initializing app after boot", e)
                    }

                    // was_tracking cleared when LocationService enters RUNNING successfully

                } catch (e: Exception) {
                    Log.e(TAG, "Critical error during boot recovery", e)
                    try { appInitializer.get().init() } catch (e2: Exception) {
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
