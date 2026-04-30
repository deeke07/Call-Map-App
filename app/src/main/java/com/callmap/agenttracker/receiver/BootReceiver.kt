package com.callmap.agenttracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.callmap.agenttracker.domain.manager.AppInitializer
import com.callmap.agenttracker.domain.manager.EventManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var eventManager: EventManager

    @Inject
    lateinit var appInitializer: AppInitializer

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 1. Log the restart event
            eventManager.logEvent(
                eventType = EventManager.DEVICE_RESTARTED,
                metadata = mapOf("reason" to "system_boot")
            )

            // 2. Re-initialize app systems (services, workers)
            appInitializer.init()
        }
    }
}
