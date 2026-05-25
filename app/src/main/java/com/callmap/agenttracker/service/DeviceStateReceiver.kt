package com.callmap.agenttracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.*
import com.callmap.agenttracker.data.manager.DeviceStateManager
import com.callmap.agenttracker.data.worker.DeviceEventSyncWorker
import com.callmap.agenttracker.domain.manager.AppInitializer
import com.callmap.agenttracker.domain.manager.EventManager
import com.callmap.agenttracker.domain.repository.DeviceEventRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DeviceStateReceiver : BroadcastReceiver() {

    @Inject
    lateinit var stateManager: DeviceStateManager

    @Inject
    lateinit var eventRepository: DeviceEventRepository

    @Inject
    lateinit var appInitializer: AppInitializer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        
        scope.launch {
            try {
                when (intent.action) {
                    Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON", Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                        Log.i("DeviceStateReceiver", "Device Rebooted. Logging and Initializing...")
                        
                        // 1. Log the event directly to Room
                        eventRepository.logEvent(EventManager.DEVICE_RESTARTED)
                        
                        // 2. Re-initialize all background systems (Workers, Services)
                        appInitializer.init()
                        
                        // 3. Trigger immediate sync
                        triggerSync(context)
                    }
                    LocationManager.PROVIDERS_CHANGED_ACTION -> {
                        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        val isEnabled = try {
                            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || 
                                           lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                        } catch (e: Exception) { false }
                        
                        stateManager.trackBinaryState(
                            stateKey = "location_hardware",
                            isEnabled = isEnabled,
                            enabledEvent = EventManager.LOCATION_ENABLED,
                            disabledEvent = EventManager.LOCATION_DISABLED
                        )
                    }
                    ConnectivityManager.CONNECTIVITY_ACTION -> {
                        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                        val network = cm.activeNetwork
                        val caps = cm.getNetworkCapabilities(network)
                        val isOnline = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                        
                        stateManager.trackBinaryState(
                            stateKey = "network_status",
                            isEnabled = isOnline,
                            enabledEvent = EventManager.DEVICE_ONLINE,
                            disabledEvent = EventManager.DEVICE_OFFLINE
                        )
                        
                        if (isOnline) {
                            triggerSync(context)
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun triggerSync(context: Context) {
        val workRequest = OneTimeWorkRequestBuilder<DeviceEventSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "DeviceEventSync_Immediate_Receiver",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }
}
