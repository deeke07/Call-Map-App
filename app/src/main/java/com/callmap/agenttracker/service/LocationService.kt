package com.callmap.agenttracker.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.callmap.agenttracker.data.local.entity.LocationEntity
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.domain.manager.SyncManager
import com.callmap.agenttracker.domain.repository.LocationRepository
import com.callmap.agenttracker.domain.usecase.location.ShouldTrackLocationUseCase
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import kotlin.coroutines.resume
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class LocationService : Service() {

    @Inject
    lateinit var repository: LocationRepository

    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var syncManager: SyncManager

    @Inject
    lateinit var shouldTrackLocationUseCase: ShouldTrackLocationUseCase

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var wakeLock: PowerManager.WakeLock? = null
    private var trackingJob: Job? = null

    companion object {
        private const val TAG = "LocationService"
        private const val NOTIFICATION_ID = 12345
        private const val CHANNEL_ID = "location_tracking_channel"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationService::WakeLock")
        
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking()
        }
        return START_STICKY
    }

    private fun startTracking() {
        // Always show/update notification to satisfy Foreground Service requirements
        startForeground(NOTIFICATION_ID, createNotification())

        // If a job is already running, we cancel it and start a fresh one to ensure 
        // we respect any new frequency or tracking toggle changes immediately.
        trackingJob?.cancel()
        
        trackingJob = serviceScope.launch {
            try {
                Log.d(TAG, "Fetching registration for tracking start...")
                val registration = sessionManager.getRegistration().first()
                if (registration == null || !registration.trackingEnabled) {
                    Log.w(TAG, "Tracking disabled in config. Stopping.")
                    stopSelf()
                    return@launch
                }

                val frequencyMillis = if (registration.locationFrequency <= 3600) {
                    registration.locationFrequency * 1000L
                } else {
                    registration.locationFrequency
                }

                Log.i(TAG, "Strict Interval Loop Started: ${frequencyMillis / 1000}s")

                while (isActive) {
                    val cycleStartTime = System.currentTimeMillis()
                    
                    val registrationLatest = sessionManager.getRegistration().first()
                    if (shouldTrackLocationUseCase(cycleStartTime, registrationLatest)) {
                        fetchAndProcessLocation()
                    } else {
                        Log.d(TAG, "Skipping tracking cycle: Outside scheduled tracking window.")
                    }

                    // Calculate drift to maintain exact interval
                    val fetchDuration = System.currentTimeMillis() - cycleStartTime
                    val nextDelay = (frequencyMillis - fetchDuration).coerceAtLeast(2000L)
                    
                    Log.d(TAG, "Interval complete. Sleeping for ${nextDelay / 1000}s...")
                    delay(nextDelay)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Loop Error: ${e.message}. Retrying in 30s.", e)
                    delay(30000)
                    startTracking() 
                }
            }
        }
    }

    private suspend fun fetchAndProcessLocation() {
        // CPU must stay awake during the 5-25 seconds of GPS search
        wakeLock?.acquire(30000L) 

        try {
            val cts = CancellationTokenSource()
            
            // getCurrentLocation forces the system to wake up GPS and get a FRESH fix.
            // Unlike requestLocationUpdates, this won't "dump" multiple old locations.
            val location = withTimeoutOrNull(25000L) { 
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cts.token
                ).await()
            }

            if (location != null) {
                val age = System.currentTimeMillis() - location.time
                if (age < 60000) { // Accept if fix is < 1 min old
                    Log.i(TAG, "✓ Fresh Fix: ${location.latitude}, ${location.longitude} (Acc: ${location.accuracy}m)")
                    saveToRoom(location.latitude, location.longitude)
                } else {
                    Log.w(TAG, "Skipped stale fix (Age: ${age/1000}s)")
                }
            } else {
                Log.e(TAG, "Location fix failed: Hardware Timeout or GPS Off")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fetch Error: ${e.message}")
        } finally {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
    }

    private suspend fun saveToRoom(lat: Double, lon: Double) {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        repository.saveLocation(
            LocationEntity(
                latitude = lat,
                longitude = lon,
                batteryLevel = battery,
                recordedAt = timestamp
            )
        )
        Log.d(TAG, "Location committed to Room. Triggering sync.")
        syncManager.triggerPendingSync()
    }

    private fun stopTracking() {
        Log.i(TAG, "Stop Tracking Signal Received")
        trackingJob?.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Call-Map")
            .setContentText("Reliable tracking active")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Location Tracking", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        stopTracking()
        serviceScope.cancel()
        super.onDestroy()
    }
}

/**
 * GMS Task to Coroutine Bridge
 */
suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T? = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resume(null) }
    addOnCanceledListener { cont.resume(null) }
}
