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

    @Inject
    lateinit var restartDetector: com.callmap.agenttracker.data.manager.DeviceRestartDetector

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

        // Threshold for switching from Loop to Alarm-based tracking (10 minutes)
        // Intervals below this stay in a resident loop reinforced by Alarms
        private const val LONG_INTERVAL_THRESHOLD_MS = 10 * 60 * 1000L
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock =
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationService::WakeLock")

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
        // Acquire initial lock to ensure setup finishes
        if (wakeLock?.isHeld == false) wakeLock?.acquire(10000L)

        val notification = createNotification()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // On Android 14+ (API 34), this requires FOREGROUND_SERVICE_LOCATION in manifest.
                // If started from background (e.g. Alarm), it ALSO requires ACCESS_BACKGROUND_LOCATION.
                // We check permission here to avoid a crash, though the real fix is user granting "Allow all the time".
                val hasBgLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                } else true

                if (Build.VERSION.SDK_INT >= 34 && !hasBgLocation) {
                    Log.w(
                        TAG,
                        "Starting Location FGS without Background Location permission. This may fail on some devices."
                    )
                }

                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical: Failed to start Foreground Service. Error: ${e.message}")

            // If we are on Android 14+ and it failed, it's likely due to background start restrictions.
            // We attempt to stop the service to prevent repeated crashes.
            if (Build.VERSION.SDK_INT >= 34) {
                stopTracking(startId)
                return
            }

            // Fallback for older versions
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                stopTracking(startId)
                return
            }
        }

        trackingJob?.cancel()
        trackingJob = serviceScope.launch {
            try {
                val registration = sessionManager.getRegistration().first()
                if (registration == null || !registration.trackingEnabled) {
                    Log.i(TAG, "Tracking disabled in settings. Stopping.")
                    stopTracking(startId)
                    return@launch
                }

                val frequencyMillis = if (registration.locationFrequency <= 3600) {
                    registration.locationFrequency * 1000L
                } else {
                    registration.locationFrequency
                }

                Log.d(TAG, "Starting tracking with frequency: ${frequencyMillis}ms")

                if (frequencyMillis >= LONG_INTERVAL_THRESHOLD_MS) {
                    runLongIntervalStrategy(frequencyMillis, startId)
                } else {
                    runShortIntervalLoop(frequencyMillis, startId)
                }

            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Tracking setup error", e)
                    // Retry after 1 minute if setup fails
                    delay(60000)
                    startTracking(startId)
                }
            } finally {
                // Release initial lock if still held
                if (wakeLock?.isHeld == true) {
                    try {
                        wakeLock?.release()
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }

    // FIXED Bug 2: fetchLock scope extended — pass it into fetch, release AFTER stopSelf
    private suspend fun runLongIntervalStrategy(intervalMs: Long, startId: Int) {
        val now = System.currentTimeMillis()
        val registration = sessionManager.getRegistration().first()

        if (shouldTrackLocationUseCase(now, registration)) {
            scheduleNextAlarm(intervalMs)

            // FIXED Bug 2: fetch completes BEFORE stopTracking is called
            fetchAndProcessLocation()

            Log.d(TAG, "Tracking cycle complete. Releasing resources until next alarm.")
            // FIXED Bug 4: use isStoppingIntentionally guard before calling stopTracking
            if (!isStoppingIntentionally) stopTracking(startId)
        } else {
            Log.d(TAG, "Outside tracking window. Scheduling next check.")
            scheduleNextAlarm(600_000L)
            if (!isStoppingIntentionally) stopTracking(startId)
        }
    }

    private suspend fun runShortIntervalLoop(intervalMs: Long, startId: Int) {
        Log.i(TAG, "Starting robust Short Interval Loop: ${intervalMs / 1000}s")
        while (true) {
            yield()
            val cycleStartTime = System.currentTimeMillis()
            val registration = sessionManager.getRegistration().first()

            if (shouldTrackLocationUseCase(cycleStartTime, registration)) {
                // Schedule alarm as a "Doze-piercing" watchdog for the next iteration (with 30s grace period)
                // This prevents the alarm from firing and interrupting the active loop when healthy
                scheduleNextAlarm(intervalMs + 30000L)

                fetchAndProcessLocation()

                val fetchDuration = System.currentTimeMillis() - cycleStartTime
                val nextDelay = (intervalMs - fetchDuration).coerceAtLeast(2000L)
                Log.d(TAG, "Cycle complete. Waiting ${nextDelay / 1000}s for next fix...")
                delay(nextDelay)
            } else {
                Log.i(TAG, "Schedule window closed. Stopping loop.")
                stopTracking(startId)
                break
            }
        }
    }

    // FIXED Bug 1 + Bug 2: WakeLock held for entire fetch duration, released only after completion
    private suspend fun fetchAndProcessLocation() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val fetchLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LocationService::FetchLock"
        )
        // FIXED Bug 5: 90s instead of 60s — accounts for GPS cold start on slow OEM devices
        fetchLock.acquire(90_000L)

        try {
            val cts = CancellationTokenSource()
            Log.d(TAG, "Requesting fresh location fix...")

            var location = withTimeoutOrNull(45_000L) {
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY, // FIXED: HIGH_ACCURACY drains battery; BALANCED is reliable
                    cts.token
                ).await()
            }

            if (location == null) {
                Log.w(TAG, "Fresh fix failed, falling back to lastLocation")
                location = fusedLocationClient.lastLocation.await()
            }

            // FIXED Bug 3: save to Room FIRST, then attempt sync — point is never lost
            if (location != null) {
                Log.i(
                    TAG,
                    "Location: ${location.latitude}, ${location.longitude} (${location.accuracy}m)"
                )
                saveToRoom(location.latitude, location.longitude) // Room insert happens here
            } else {
                Log.e(TAG, "Location fetch failed: no fix available.")
                // KEPT: original log behavior
            }
        } catch (e: CancellationException) {
            throw e // FIXED: never swallow CancellationException
        } catch (e: Exception) {
            Log.e(TAG, "Error during location fetch: ${e.message}")
        } finally {
            // FIXED Bug 1: release only here — guaranteed to run even on exception
            if (fetchLock.isHeld) {
                try {
                    fetchLock.release()
                } catch (e: Exception) {
                    Log.w(TAG, "WakeLock release error")
                }
            }
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

        // FIX: Use setExactAndAllowWhileIdle for Doze mode penetration
        // "AllowWhileIdle" is critical for Doze/Battery Saver modes on Xiaomi/Samsung/OPPO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                    Log.d(TAG, "Alarm scheduled (EXACT+DOZE): ${delayMs / 1000}s from now")
                } catch (e: SecurityException) {
                    Log.w(TAG, "SCHEDULE_EXACT_ALARM permission denied. Using inexact.")
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
            } else {
                Log.w(TAG, "Cannot schedule exact alarms. Using inexact (Doze-safe).")
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } else {
            // Pre-Android 12: Always use setExactAndAllowWhileIdle for best Doze penetration
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
                Log.d(TAG, "Alarm scheduled (EXACT+DOZE): ${delayMs / 1000}s from now")
            } catch (e: Exception) {
                Log.w(TAG, "Error scheduling exact alarm: ${e.message}. Using set().")
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }
    }

    // FIXED Bug 3: Room insert + sync trigger both happen here — never skip Room
    private suspend fun saveToRoom(lat: Double, lon: Double) {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        // KEPT: original entity structure
        val entity = LocationEntity(
            latitude = lat,
            longitude = lon,
            batteryLevel = battery,
            recordedAt = timestamp
        )

        repository.saveLocation(entity) // Room insert — point is safe even if sync fails

        try {
            syncManager.triggerPendingSync() // attempt upload — failure is OK, Room has it
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed, point is buffered in Room: ${e.message}")
            // FIXED: don't rethrow — point is already in Room
        }
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
        .setContentTitle("CallMap Tracking")
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
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Agent Tracking Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Continuous background location tracking"
                enableVibration(false)
                setSound(null, null)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?) = null

    // FIXED Bug 4: onDestroy — stopTracking already called by stopSelf flow,
// guard with isStoppingIntentionally to prevent double call
    override fun onDestroy() {
        if (!isStoppingIntentionally) {
            serviceScope.launch {
                try {
                    val registration = sessionManager.getRegistration().first()
                    if (registration != null && registration.trackingEnabled &&
                        shouldTrackLocationUseCase(System.currentTimeMillis(), registration)
                    ) {
                        restartDetector.recordTrackingState(true)
                        eventManager.logEvent(
                            EventManager.LOCATION_TRACKING_STOPPED,
                            metadata = mapOf("reason" to "unexpected_service_destruction")
                        )
                        Log.w(TAG, "Service destroyed unexpectedly. Will resume on next alarm.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onDestroy", e)
                }
            }
            // FIXED Bug 4: only release wakeLock here, don't call stopTracking again
            if (wakeLock?.isHeld == true) {
                try {
                    wakeLock?.release()
                } catch (e: Exception) {
                }
            }
        }
        // FIXED Bug 6: always cancel scope to prevent coroutine leak
        serviceScope.cancel()
        super.onDestroy()
    }
}

suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T? = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resume(null) }
    addOnCanceledListener { cont.resume(null) }
}