package com.callmap.agenttracker.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.callmap.agenttracker.data.manager.AlarmScheduler
import com.callmap.agenttracker.data.manager.ServiceRestartManager
import com.callmap.agenttracker.util.LocationFrequencyParser
import com.callmap.agenttracker.util.LocationQualityGate
import com.callmap.agenttracker.util.TrackingNotificationHelper
import android.os.*
import android.util.Log
import com.callmap.agenttracker.data.local.entity.LocationEntity
import com.callmap.agenttracker.domain.manager.EventManager
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.domain.manager.SyncManager
import com.callmap.agenttracker.domain.repository.LocationRepository
import com.callmap.agenttracker.domain.usecase.location.ShouldTrackLocationUseCase
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.coroutines.resume

@AndroidEntryPoint
class LocationService : Service() {

    // ── Injected ─────────────────────────────────────────────────────────────
    @Inject lateinit var repository: LocationRepository
    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var syncManager: SyncManager
    @Inject lateinit var shouldTrackLocationUseCase: ShouldTrackLocationUseCase
    @Inject lateinit var eventManager: EventManager
    @Inject lateinit var restartDetector: com.callmap.agenttracker.data.manager.DeviceRestartDetector
    @Inject lateinit var networkObserver: com.callmap.agenttracker.util.NetworkObserver
    @Inject lateinit var alarmScheduler: AlarmScheduler
    @Inject lateinit var serviceRestartManager: ServiceRestartManager
    @Inject lateinit var stateManager: com.callmap.agenttracker.data.manager.DeviceStateManager

    // ── State ─────────────────────────────────────────────────────────────────
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var wakeLock: PowerManager.WakeLock? = null
    private var trackingJob: Job? = null

    // Tracking parameters to avoid redundant system updates
    private var lastIntervalMs: Long = -1L
    private var lastPriority: Int = -1

    /**
     * Reactive trigger to force a tracking re-evaluation (e.g., when app foregrounds
     * and permissions might have changed).
     */
    private val manualPoke = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Thread-safe state machine.
     * IDLE → RUNNING   : compareAndSet wins, loop starts
     * RUNNING → STOPPING: initiateStop() called
     * RUNNING → IDLE   : loop exits normally via finally
     * STOPPING → IDLE  : finally resets after cancel
     */
    private enum class TrackingState { IDLE, RUNNING, STOPPING }
    private val trackingState = AtomicReference(TrackingState.IDLE)

    // Setup retry counter — reset on successful loop entry
    private var setupRetryCount = 0
    private var trackingCycleCount = 0

    /**
     * Battery health tier — drives GPS priority + timeout decisions.
     *
     * NORMAL  (> 15%) : PRIORITY_HIGH_ACCURACY,    45s timeout
     * LOW     (6–15%) : PRIORITY_BALANCED_POWER,   60s timeout
     * CRITICAL(1–5%)  : PRIORITY_LOW_POWER,        90s timeout + lastLocation fallback first
     * DEAD    (< 1%)  : lastLocation only — no active GPS scan
     *
     * Why this matters at 3%:
     *   The OS aggressively suspends GPS hardware under critical battery.
     *   PRIORITY_HIGH_ACCURACY requests the GPS chip to stay on —
     *   the OS may deny this or the chip may not respond within timeout.
     *   PRIORITY_LOW_POWER uses cell towers + WiFi — much cheaper, still valid.
     */
    private enum class BatteryTier { NORMAL, LOW, CRITICAL, DEAD }

    // ── Constants ─────────────────────────────────────────────────────────────
    companion object {
        private const val TAG = "LocationService"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP  = "ACTION_STOP"

        private const val MAX_SETUP_RETRIES = 3

        private const val PERSIST_STATE_EVERY_CYCLES = 5

        /** Consecutive failed cycles in-window before LOCATION_TRACKING_STOPPED is sent once. */
        private const val IN_WINDOW_FAILURE_THRESHOLD = 3

        private const val ENABLE_VERBOSE_LOGS = false
    }

    private fun logVerbose(message: String) {
        if (ENABLE_VERBOSE_LOGS) Log.d(TAG, message)
    }

    private suspend fun auditDeviceState() {
        // 1. Audit Permissions
        val permissions = mutableListOf(
            android.Manifest.permission.RECORD_AUDIO to "RECORD_AUDIO",
            android.Manifest.permission.ACCESS_FINE_LOCATION to "LOCATION",
            android.Manifest.permission.READ_PHONE_STATE to "PHONE_STATE",
            android.Manifest.permission.READ_CALL_LOG to "CALL_LOG",
            android.Manifest.permission.READ_CONTACTS to "CONTACTS",
            android.Manifest.permission.CALL_PHONE to "CALL_PHONE"
        )

        @Suppress("DEPRECATION")
        permissions.add(android.Manifest.permission.PROCESS_OUTGOING_CALLS to "OUTGOING_CALLS")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION to "BACKGROUND_LOCATION")
        }

        permissions.forEach { (perm, name) ->
            val isGranted = ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
            val enabledEvent = if (name == "LOCATION") EventManager.LOCATION_ENABLED else EventManager.PERMISSION_ENABLED
            val disabledEvent = if (name == "LOCATION") EventManager.LOCATION_DISABLED else EventManager.PERMISSION_DISABLED

            stateManager.trackBinaryState(
                stateKey = "perm",
                isEnabled = isGranted,
                enabledEvent = enabledEvent,
                disabledEvent = disabledEvent,
                permissionName = name
            )
        }

        // 2. Audit Hardware
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isHardwareEnabled = try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || 
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) { false }
        
        stateManager.trackBinaryState(
            stateKey = "location_hardware",
            isEnabled = isHardwareEnabled,
            enabledEvent = EventManager.LOCATION_ENABLED,
            disabledEvent = EventManager.LOCATION_DISABLED
        )

        // 3. Audit Battery Optimization
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isOptimizing = !pm.isIgnoringBatteryOptimizations(packageName)
        stateManager.trackBinaryState(
            stateKey = "battery_optimization",
            isEnabled = isOptimizing,
            enabledEvent = EventManager.BATTERY_OPTIMIZATION_ENABLED,
            disabledEvent = null
        )

        // 4. Audit Network Status
        stateManager.trackBinaryState(
            stateKey = "network_status",
            isEnabled = networkObserver.isConnected(),
            enabledEvent = EventManager.DEVICE_ONLINE,
            disabledEvent = EventManager.DEVICE_OFFLINE
        )

    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationService::WakeLock")
        TrackingNotificationHelper.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        logVerbose("onStartCommand action=$action")

        // CRITICAL: Satisfy Android 12+ Foreground Service start requirements immediately.
        // We call this BEFORE any branch logic to ensure the 5-second handshake is met.
        val promoted = promoteForeground(startId)

        if (!promoted) {
            // If we couldn't promote to foreground, we must stop to avoid the crash.
            if (startId != 0) stopSelf(startId) else stopSelf()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_STOP  -> initiateStop(startId)
            ACTION_START -> {
                serviceScope.launch { auditDeviceState() }
                startTrackingGuarded(startId)
            }
            else         -> {
                serviceScope.launch { auditDeviceState() }
                startTrackingGuarded(startId)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        val wasActive = trackingState.get() != TrackingState.IDLE
        if (wasActive) persistRecoveryStateAndScheduleRestart()
        trackingState.set(TrackingState.IDLE)
        trackingJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (trackingState.get() == TrackingState.RUNNING) {
            persistRecoveryStateAndScheduleRestart()
        }
    }

    override fun onBind(intent: Intent?) = null

    // ── Start guard ───────────────────────────────────────────────────────────

    private fun startTrackingGuarded(startId: Int) {
        if (!trackingState.compareAndSet(TrackingState.IDLE, TrackingState.RUNNING)) {
            // Already running, foreground was promoted in onStartCommand
            return
        }
        startTracking(startId)
    }

    // ── Core tracking ─────────────────────────────────────────────────────────

    private fun startTracking(startId: Int) {
        acquireWakeLock(30_000L)

        trackingJob?.cancel()
        trackingJob = serviceScope.launch {
            try {
                // Initial Audit on start
                auditDeviceState()

                // Continuous Observation of Registration and Battery
                combine(
                    sessionManager.getRegistration(),
                    batteryTierFlow()
                ) { reg, tier -> reg to tier }
                    .collect { (registration, tier) ->
                        if (registration == null || !registration.trackingEnabled) {
                            Log.i(TAG, "Tracking disabled via config. Stopping.")
                            withContext(Dispatchers.Main) { initiateStop(startId) }
                            return@collect
                        }

                        // Success path: Reset setup errors
                        setupRetryCount = 0
                        restartDetector.recordTrackingState(true)
                        restartDetector.clearRestartState()

                        val intervalMs = LocationFrequencyParser.fromStoredValue(registration.locationFrequency)
                        updateTrackingParameters(intervalMs, tier, registration.locationHighAccuracy)
                    }

            } catch (e: Exception) {
                if (e !is CancellationException) {
                    handleSetupError(e, startId)
                }
            } finally {
                // Cleanup: Stop updates if the coroutine is cancelled
                fusedLocationClient.removeLocationUpdates(locationCallback)
                trackingState.set(TrackingState.IDLE)
                releaseWakeLock()
            }
        }
    }

    private fun promoteForeground(startId: Int): Boolean {
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            TrackingNotificationHelper.startForegroundSafely(
                this,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            TrackingNotificationHelper.startForegroundSafely(this)
        }

        if (!started) {
            Log.e(TAG, "FGS start failed — scheduling retry")
            // Log failure asynchronously
            serviceScope.launch {
                logTrackingFailureInWindow("foreground_service_start_failed")
            }
            trackingState.set(TrackingState.IDLE)
            alarmScheduler.scheduleWatchdogAlarm(60_000L)
            return false
        }
        return true
    }

    // ── Interval strategies ───────────────────────────────────────────────────

    private fun batteryTierFlow(): Flow<BatteryTier> = flow {
        while (currentCoroutineContext().isActive) {
            emit(getBatteryTier())
            delay(5 * 60_000L) // Re-evaluate battery tier every 5 mins
        }
    }.distinctUntilChanged()

    private fun updateTrackingParameters(intervalMs: Long, tier: BatteryTier, highAccuracy: Boolean) {
        val priority = when (tier) {
            BatteryTier.NORMAL   -> if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
            BatteryTier.LOW      -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            BatteryTier.CRITICAL -> Priority.PRIORITY_LOW_POWER
            BatteryTier.DEAD     -> Priority.PRIORITY_PASSIVE
        }

        if (intervalMs == lastIntervalMs && priority == lastPriority) {
            logVerbose("Tracking parameters unchanged: Interval=${intervalMs}ms, Priority=$priority")
            return
        }

        lastIntervalMs = intervalMs
        lastPriority = priority

        applyLocationUpdates(intervalMs, priority)
    }

    private fun applyLocationUpdates(intervalMs: Long, priority: Int) {
        if (!hasLocationPermission()) return
        try {
            // Remove previous updates before applying new ones to ensure clean state
            fusedLocationClient.removeLocationUpdates(locationCallback)

            val request = LocationRequest.Builder(priority, intervalMs)
                .setMinUpdateIntervalMillis(intervalMs / 2)
                .setWaitForAccurateLocation(false)
                .build()

            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.i(TAG, "LOCATION_UPDATES_ACTIVE: Interval=${intervalMs/1000}s, Priority=$priority")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply location updates: ${e.message}")
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            serviceScope.launch {
                persistLocation(location, getBatteryLevel(), "continuous_callback", allowLenient = false)
            }
        }
    }

    // ── Room persistence ──────────────────────────────────────────────────────

    /**
     * Saves to Room BEFORE sync attempt.
     * Point is safe in Room even if:
     *   - Network is down
     *   - Server returns 5xx
     *   - Process is killed immediately after save
     *
     * source param is for diagnostics — tells you in logs/server which
     * code path produced this point (GPS vs lastLocation vs cell tower).
     */
    private suspend fun persistLocation(
        location: Location,
        battery: Int,
        source: String,
        allowLenient: Boolean,
        isDesperate: Boolean = false
    ): Boolean {
        Log.d(TAG, "LOCATION_RECEIVED: Source=$source, Lat=${location.latitude}, Lon=${location.longitude}, Accuracy=${location.accuracy}")
        
        val quality = LocationQualityGate.validate(location, allowLenient, isDesperate)
        if (!quality.accepted) {
            if (!allowLenient && !isDesperate) {
                logMissedPoint(battery, getBatteryTier(), "quality_${quality.reason}")
            }
            return false
        }

        // Active Window Persistence Check
        val now = System.currentTimeMillis()
        val registration = sessionManager.getRegistration().first()
        val inWindow = shouldTrackLocationUseCase(now, registration)

        if (!inWindow) {
            Log.i(TAG, "LOCATION_DISCARDED_OUTSIDE_ACTIVE_WINDOW: Point at $now discarded (Window: ${registration?.trackingStartTime}-${registration?.trackingEndTime})")
            return true // Return true so the loop considers the "fetch" successful and doesn't trigger failure retries
        }

        return saveToRoomSafely(location.latitude, location.longitude, battery, source)
    }

    private suspend fun saveToRoomSafely(
        lat: Double,
        lon: Double,
        battery: Int,
        source: String = "unknown"
    ): Boolean {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        try {
            Log.w(TAG, "LOCATION_SAVED: ($lat,$lon) via $source")
            repository.saveLocation(
                LocationEntity(
                    latitude     = lat,
                    longitude    = lon,
                    batteryLevel = battery,
                    recordedAt   = timestamp
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Room save failed: ${e.message}")
            logMissedPoint(battery, getBatteryTier(), "room_insert_failed")
            return false
        }

        runCatching { syncManager.triggerPendingSync() }
        return true
    }

    // ── Battery helpers ───────────────────────────────────────────────────────

    /**
     * Returns battery tier based on current level AND power save mode.
     * Power save mode alone drops tier by one level.
     */
    private fun getBatteryTier(): BatteryTier {
        val level = getBatteryLevel()
        val isPowerSave = (getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode

        return when {
            level < 0                      -> BatteryTier.NORMAL   // unknown — assume normal
            level <= 1                     -> BatteryTier.DEAD
            level <= 5                     -> BatteryTier.CRITICAL
            level <= 15 || isPowerSave     -> BatteryTier.LOW
            else                           -> BatteryTier.NORMAL
        }
    }

    private fun getBatteryLevel(): Int = try {
        (getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    } catch (e: Exception) { -1 }

    // ── Stop ──────────────────────────────────────────────────────────────────

    private fun initiateStop(startId: Int? = null) {
        trackingState.set(TrackingState.STOPPING)
        alarmScheduler.clearLocationWakeSchedule()
        restartDetector.recordTrackingState(false)
        trackingJob?.cancel()
        
        // Stop persistent location updates
        fusedLocationClient.removeLocationUpdates(locationCallback)

        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (startId != null) stopSelf(startId) else stopSelf()
    }

    // ── Error handling ────────────────────────────────────────────────────────

    private suspend fun handleSetupError(e: Exception, startId: Int) {
        setupRetryCount++
        Log.e(TAG, "Setup error ($setupRetryCount): ${e.message}. Retrying with backoff...")

        val backoffMs = (60_000L * setupRetryCount).coerceAtMost(300_000L)
        delay(backoffMs)

        trackingState.set(TrackingState.IDLE)
        withContext(Dispatchers.Main) { startTrackingGuarded(startId) }
    }

    private fun persistRecoveryStateAndScheduleRestart() {
        if (alarmScheduler.hasUpcomingLocationWake()) {
            logVerbose("Recovery skipped — next location wake already scheduled")
            return
        }
        runCatching {
            restartDetector.recordTrackingState(true)
            val delayMs = alarmScheduler.lastScheduledIntervalMs().coerceAtLeast(60_000L)
            serviceRestartManager.scheduleServiceRestart(delayMs)
        }.onFailure { Log.e(TAG, "Recovery schedule failed: ${it.message}") }
    }

    /**
     * LOCATION_TRACKING_STOPPED — only while the schedule window is active and location
     * cannot be obtained (not on normal service stop / ephemeral cycle).
     */
    private fun logTrackingFailureInWindow(reason: String, extra: Map<String, String> = emptyMap()) {
        serviceScope.launch {
            val registration = sessionManager.getRegistration().first()
            val inWindow = registration?.trackingEnabled == true &&
                shouldTrackLocationUseCase(System.currentTimeMillis(), registration)
            if (!inWindow) return@launch

            runCatching {
                val metadata = mutableMapOf("reason" to reason)
                metadata.putAll(extra)
             //   eventManager.logEvent(EventManager.LOCATION_TRACKING_STOPPED, metadata = metadata)
                Log.w(TAG, "Tracking failed (in window): $reason")
            }
        }
    }

    private fun logMissedPoint(battery: Int, tier: BatteryTier, reason: String) {
        Log.w(TAG, "Missed point [Batt: $battery%, Tier: $tier]: $reason")
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine
    }

    private fun isLocationHardwareEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun acquireWakeLock(timeoutMs: Long) {
        runCatching { if (wakeLock?.isHeld == false) wakeLock?.acquire(timeoutMs) }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
    }

}

// Task.await() — bridges GMS Task to coroutine safely
suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T? =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener  { if (cont.isActive) cont.resume(it) }
        addOnFailureListener  { if (cont.isActive) cont.resume(null) }
        addOnCanceledListener { if (cont.isActive) cont.resume(null) }
    }