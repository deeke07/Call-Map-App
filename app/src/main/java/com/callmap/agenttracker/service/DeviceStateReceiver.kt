package com.callmap.agenttracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.UserManager
import android.util.Log
import androidx.work.*
import com.callmap.agenttracker.data.manager.DeviceStateManager
import com.callmap.agenttracker.data.worker.DeviceEventSyncWorker
import com.callmap.agenttracker.domain.manager.EventManager
import com.callmap.agenttracker.domain.repository.DeviceEventRepository
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DeviceStateReceiver : BroadcastReceiver() {

    @Inject
    lateinit var stateManager: Lazy<DeviceStateManager>

    @Inject
    lateinit var eventRepository: Lazy<DeviceEventRepository>

    override fun onReceive(context: Context, intent: Intent) {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        if (userManager != null && !userManager.isUserUnlocked) {
            Log.w("DeviceStateReceiver", "Ignoring ${intent.action} during Direct Boot (User Locked)")
            return
        }

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        
        scope.launch {
            try {
                when (intent.action) {
                    LocationManager.PROVIDERS_CHANGED_ACTION -> {
                        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        val isEnabled = try {
                            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || 
                                           lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                        } catch (e: Exception) { false }
                        
                        stateManager.get().trackBinaryState(
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
                        
                        stateManager.get().trackBinaryState(
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
                scope.cancel()
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
