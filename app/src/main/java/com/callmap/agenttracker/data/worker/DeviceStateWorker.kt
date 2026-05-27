package com.callmap.agenttracker.data.worker

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.callmap.agenttracker.data.manager.DeviceStateManager
import com.callmap.agenttracker.domain.manager.EventManager
import com.callmap.agenttracker.domain.manager.ServiceManager
import com.callmap.agenttracker.domain.repository.DeviceEventRepository
import com.callmap.agenttracker.domain.usecase.location.ShouldTrackLocationUseCase
import com.callmap.agenttracker.service.LocationService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

@HiltWorker
class DeviceStateWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val stateManager: DeviceStateManager,
    private val serviceManager: ServiceManager,
    private val eventRepository: DeviceEventRepository,
    private val shouldTrackLocationUseCase: ShouldTrackLocationUseCase
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DeviceStateWorker"
        private const val POLL_INTERVAL_MINUTES = 2L
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Executing single state check...")
        
        try {
            // Heartbeat disabled to prevent 422 API errors
            // eventRepository.logEvent(EventManager.HEARTBEAT)

            checkAllStates()
            
            // Immediately sync any changes detected (permissions, SIM, hardware, heartbeat)
            eventRepository.syncPendingEvents()
            
            // Schedule the NEXT check in 2 minutes
            scheduleNextCheck(context)
            
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "State check failed", e)
            return Result.retry()
        }
    }

    private suspend fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO to "RECORD_AUDIO",
            Manifest.permission.ACCESS_FINE_LOCATION to "LOCATION",
            Manifest.permission.READ_PHONE_STATE to "PHONE_STATE",
            Manifest.permission.READ_CALL_LOG to "CALL_LOG",
            Manifest.permission.READ_CONTACTS to "CONTACTS"
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION to "BACKGROUND_LOCATION")
        }

        permissions.forEach { (perm, name) ->
            val isGranted = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            
            val storageKey = "perm_$name"
            val lastKnownStates = stateManager.sessionManager.getDeviceStates().first()
            val wasEnabled = lastKnownStates[storageKey] == "true"

            stateManager.trackBinaryState(
                stateKey = "perm",
                isEnabled = isGranted,
                enabledEvent = EventManager.PERMISSION_ENABLED,
                disabledEvent = EventManager.PERMISSION_DISABLED,
                permissionName = name
            )

            // If it was DISABLED and is now ENABLED, and it's the LOCATION permission, restart tracking
            if (isGranted && !wasEnabled && name == "LOCATION") {
                Log.i(TAG, "Location permission restored! Checking if service should start.")
                val registration = stateManager.sessionManager.getRegistration().first()
                serviceManager.handleServiceLifecycle(registration?.trackingEnabled == true)
            }
        }
    }

    private suspend fun checkLocationHardware() {
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

    private suspend fun checkBatteryOptimization() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isOptimizing = !pm.isIgnoringBatteryOptimizations(context.packageName)
        
        stateManager.trackBinaryState(
            stateKey = "battery_optimization",
            isEnabled = isOptimizing,
            enabledEvent = EventManager.BATTERY_OPTIMIZATION_ENABLED,
            disabledEvent = null
        )
    }

    private suspend fun checkBackgroundRestriction() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            stateManager.trackBinaryState(
                stateKey = "bg_restricted",
                isEnabled = am.isBackgroundRestricted,
                enabledEvent = EventManager.BACKGROUND_ACTIVITY_RESTRICTED,
                disabledEvent = null
            )
        }
    }

    private suspend fun checkNetworkState() {
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
    }

    private suspend fun checkAllStates() {
        checkPermissions()
        checkLocationHardware()
        checkBatteryOptimization()
        checkBackgroundRestriction()
        checkNetworkState()
    }

    private fun scheduleNextCheck(context: Context) {
        val workRequest = OneTimeWorkRequestBuilder<DeviceStateWorker>()
            .setInitialDelay(POLL_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.NONE) // Heartbeat should run even if low battery/no net (it will queue)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "DeviceStateWorker_Periodic",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            workRequest
        )
    }


}
