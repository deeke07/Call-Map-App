package com.callmap.agenttracker.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.callmap.agenttracker.data.local.entity.LocationEntity
import com.callmap.agenttracker.domain.manager.EventManager
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.domain.manager.SyncManager
import com.callmap.agenttracker.domain.repository.LocationRepository
import com.callmap.agenttracker.domain.usecase.location.ShouldTrackLocationUseCase
import com.callmap.agenttracker.receiver.ScheduleReceiver
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

    @Inject
    lateinit var eventManager: EventManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var wakeLock: PowerManager.WakeLock? = null
    private var trackingJob: Job? = null
    private var isStoppingIntentionally = false

    companion object {
        private const val TAG = "LocationService"
        private const val NOTIFICATION_ID = 12345
        private const val CHANNEL_ID = "location_tracking_v4"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        
        // Threshold for switching from Loop to Alarm-based tracking (5 minutes)
        private const val LONG_INTERVAL_THRESHOLD_MS = 5 * 60 * 1000L 
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationService::WakeLock")
        
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand: action=$action, startId=$startId")

        when (action) {
            ACTION_STOP -> stopTracking(startId)
            ACTION_START -> startTracking(startId)
            else -> startTracking(startId) // Resumes on system restart
        }
        return START_STICKY
    }

    private fun startTracking(startId: Int) {
        isStoppingIntentionally = false
        if (wakeLock?.isHeld == false) wakeLock?.acquire(10000L)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        trackingJob?.cancel()
        trackingJob = serviceScope.launch {
            try {
                val registration = sessionManager.getRegistration().first()
                if (registration == null || !registration.trackingEnabled) {
                    stopTracking(startId)
                    return@launch
                }

                val frequencyMillis = if (registration.locationFrequency <= 3600) {
                    registration.locationFrequency * 1000L
                } else {
                    registration.locationFrequency
                }

                if (frequencyMillis >= LONG_INTERVAL_THRESHOLD_MS) {
                    runLongIntervalStrategy(frequencyMillis, startId)
                } else {
                    runShortIntervalLoop(frequencyMillis, startId)
                }

            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Tracking setup error", e)
                    delay(30000)
                    startTracking(startId)
                }
            }
        }
    }

    private suspend fun runLongIntervalStrategy(intervalMs: Long, startId: Int) {
        val now = System.currentTimeMillis()
        val registration = sessionManager.getRegistration().first()

        if (shouldTrackLocationUseCase(now, registration)) {
            Log.i(TAG, "Long Interval Strategy: Fetching location fix...")
            fetchAndProcessLocation()
            
            // Log Heartbeat
            eventManager.logEvent("TRACKING_HEARTBEAT", metadata = mapOf("strategy" to "long_interval", "interval" to intervalMs))

            Log.i(TAG, "Scheduling next alarm-based fix in ${intervalMs / 1000}s. Service remains in foreground.")
            scheduleNextAlarm(intervalMs)
        } else {
            Log.d(TAG, "Outside tracking window. Stopping tracking service.")
            eventManager.logEvent("TRACKING_STOPPED_BY_SCHEDULE", metadata = mapOf("reason" to "outside_window"))
            stopTracking(startId)
        }
    }

    private suspend fun runShortIntervalLoop(intervalMs: Long, startId: Int) {
        Log.i(TAG, "Short Interval Loop: ${intervalMs / 1000}s")
        var lastHeartbeat = 0L
        while (true) {
            yield()
            val cycleStartTime = System.currentTimeMillis()
            val registration = sessionManager.getRegistration().first()

            if (shouldTrackLocationUseCase(cycleStartTime, registration)) {
                fetchAndProcessLocation()
                
                // Heartbeat every 15 minutes in short loop to avoid log spam
                if (cycleStartTime - lastHeartbeat > 15 * 60 * 1000L) {
                    eventManager.logEvent("TRACKING_HEARTBEAT", metadata = mapOf("strategy" to "short_loop", "interval" to intervalMs))
                    lastHeartbeat = cycleStartTime
                }

                val fetchDuration = System.currentTimeMillis() - cycleStartTime
                val nextDelay = (intervalMs - fetchDuration).coerceAtLeast(2000L)
                delay(nextDelay)
            } else {
                Log.d(TAG, "Outside tracking window (Loop). Stopping.")
                eventManager.logEvent("TRACKING_STOPPED_BY_SCHEDULE", metadata = mapOf("reason" to "outside_window_loop"))
                stopTracking(startId)
                break
            }
        }
    }

    private suspend fun fetchAndProcessLocation() {
        if (wakeLock?.isHeld == false) wakeLock?.acquire(30000L)
        try {
            val cts = CancellationTokenSource()
            val location = withTimeoutOrNull(25000L) {
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()
            }

            if (location != null) {
                Log.i(TAG, "Location Fix: ${location.latitude}, ${location.longitude}")
                saveToRoom(location.latitude, location.longitude)
            } else {
                Log.e(TAG, "Location Fix Timeout")
            }
        } finally {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
    }

    private fun scheduleNextAlarm(delayMs: Long) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        // Target ScheduleReceiver for better wake reliability
        val intent = Intent(this, ScheduleReceiver::class.java).apply {
            action = ScheduleReceiver.ACTION_LOCATION_ALARM
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            this, 1001, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAt = System.currentTimeMillis() + delayMs
        
        // Use setExactAndAllowWhileIdle for Doze mode penetration
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
    }

    private suspend fun saveToRoom(lat: Double, lon: Double) {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        repository.saveLocation(LocationEntity(latitude = lat, longitude = lon, batteryLevel = battery, recordedAt = timestamp))
        syncManager.triggerPendingSync()
    }

    private fun stopTracking(startId: Int? = null) {
        isStoppingIntentionally = true
        trackingJob?.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (startId != null) {
            stopSelf(startId)
        } else {
            stopSelf()
        }
    }

    private fun createNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Call-Map Tracking")
        .setContentText("Agent tracking is active")
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setCategory(Notification.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Agent Tracking Service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Continuous background location tracking"
                enableVibration(false)
                setSound(null, null)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?) = null
    override fun onDestroy() {
        if (!isStoppingIntentionally) {
            serviceScope.launch {
                val registration = sessionManager.getRegistration().first()
                if (registration != null && registration.trackingEnabled &&
                    shouldTrackLocationUseCase(System.currentTimeMillis(), registration)
                ) {
                    eventManager.logEvent(
                        EventManager.LOCATION_TRACKING_STOPPED,
                        metadata = mapOf("reason" to "unexpected_service_destruction")
                    )
                }
            }
        }
        stopTracking()
        serviceScope.cancel()
        super.onDestroy()
    }
}

suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T? = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resume(null) }
    addOnCanceledListener { cont.resume(null) }
}
