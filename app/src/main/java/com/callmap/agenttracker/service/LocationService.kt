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
import kotlinx.coroutines.flow.first
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

    // ── State ─────────────────────────────────────────────────────────────────
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var wakeLock: PowerManager.WakeLock? = null
    private var trackingJob: Job? = null

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
        when (action) {
            ACTION_STOP  -> initiateStop(startId)
            ACTION_START -> startTrackingGuarded(startId)
            else         -> startTrackingGuarded(startId)
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
            runCatching { TrackingNotificationHelper.startForegroundSafely(this) }
            return
        }
        startTracking(startId)
    }

    // ── Core tracking ─────────────────────────────────────────────────────────

    private fun startTracking(startId: Int) {
        acquireWakeLock(10_000L)

        if (!promoteForeground(startId)) return  // resets state internally on failure

        trackingJob?.cancel()
        trackingJob = serviceScope.launch {
            try {
                val registration = sessionManager.getRegistration().first()

                if (registration == null || !registration.trackingEnabled) {
                    withContext(Dispatchers.Main) { initiateStop(startId) }
                    return@launch
                }

                val frequencyMs = LocationFrequencyParser.fromStoredValue(registration.locationFrequency)
                Log.i(TAG, "Tracking active — interval ${frequencyMs / 1000}s")
                setupRetryCount = 0
                trackingCycleCount = 0
                restartDetector.recordTrackingState(true)
                restartDetector.clearRestartState()

                // Same in-process loop for all intervals — avoids stopping FGS on long intervals.
                runTrackingLoop(frequencyMs, startId)

            } catch (e: CancellationException) {
                // expected on ephemeral stop
            } catch (e: Exception) {
                handleSetupError(e, startId)
            } finally {
                trackingState.set(TrackingState.IDLE)
                releaseWakeLock()
            }
        }
    }

    private fun promoteForeground(startId: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val hasBgLocation = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (Build.VERSION.SDK_INT >= 34 && !hasBgLocation) {
                logVerbose("Missing ACCESS_BACKGROUND_LOCATION")
            }
        }

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            TrackingNotificationHelper.startForegroundSafely(
                this,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            TrackingNotificationHelper.startForegroundSafely(this)
        }

        if (!started) {
            Log.e(TAG, "FGS start failed — alarm retry scheduled")
            serviceScope.launch {
                logTrackingFailureInWindow("foreground_service_start_failed")
            }
            trackingState.set(TrackingState.IDLE)
            releaseWakeLock()
            alarmScheduler.scheduleWatchdogAlarm(60_000L)
            if (startId != 0) stopSelf(startId) else stopSelf()
            return false
        }
        return true
    }

    // ── Interval strategies ───────────────────────────────────────────────────

    private suspend fun runTrackingLoop(intervalMs: Long, startId: Int) {
        var consecutiveFailures = 0
        var failureEventLogged = false

        while (currentCoroutineContext().isActive) {
            val cycleStart = System.currentTimeMillis()
            val registration = sessionManager.getRegistration().first()
            if (!currentCoroutineContext().isActive) break

            if (!shouldTrackLocationUseCase(cycleStart, registration)) {
                if (trackingState.get() != TrackingState.STOPPING) {
                    withContext(Dispatchers.Main) { initiateStop(startId) }
                }
                break
            }

            trackingCycleCount++
            if (trackingCycleCount % PERSIST_STATE_EVERY_CYCLES == 0) {
                restartDetector.recordTrackingState(true)
            }

            val fetched = fetchWithBatteryAwareness(registration)
            if (!currentCoroutineContext().isActive) break

            if (fetched) {
                consecutiveFailures = 0
                failureEventLogged = false
            } else {
                consecutiveFailures++
                if (consecutiveFailures >= IN_WINDOW_FAILURE_THRESHOLD && !failureEventLogged) {
                    val failReason = when {
                        !hasLocationPermission() -> "permission_missing"
                        else -> "consecutive_location_failures"
                    }
                    logTrackingFailureInWindow(
                        failReason,
                        mapOf("failures" to consecutiveFailures.toString())
                    )
                    failureEventLogged = true
                }
                val retryDelay = when (consecutiveFailures) {
                    1    -> 30_000L
                    2    -> 60_000L
                    else -> intervalMs
                }
                scheduleEphemeralWakeAndStop(retryDelay.coerceAtMost(intervalMs), startId)
                return
            }

            val elapsed = System.currentTimeMillis() - cycleStart
            val sleepMs = (intervalMs - elapsed).coerceAtLeast(5_000L)
            scheduleEphemeralWakeAndStop(sleepMs, startId)
            return
        }
    }

    /** Stops FGS after scheduling the next alarm — no persistent agent-visible notification. */
    private suspend fun scheduleEphemeralWakeAndStop(delayMs: Long, startId: Int) {
        alarmScheduler.scheduleWatchdogAlarm(delayMs)
        restartDetector.recordTrackingState(true)
        logVerbose("Next wake in ${delayMs / 1000}s (ephemeral stop)")
        withContext(Dispatchers.Main) { stopAfterEphemeralCycle(startId) }
    }

    private fun stopAfterEphemeralCycle(startId: Int) {
        trackingState.set(TrackingState.IDLE)
        trackingJob?.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    // ── Battery-aware location fetch ──────────────────────────────────────────

    /**
     * The core battery-aware fetch. This is the fix for 3% battery failures.
     *
     * Strategy:
     *   NORMAL  battery: GPS chip on, high accuracy, 45s timeout
     *   LOW     battery: Network/WiFi positioning, 60s timeout
     *   CRITICAL battery: Low-power positioning, try lastLocation first, 90s timeout
     *   DEAD    battery: lastLocation ONLY — no GPS scan at all
     *
     * Why lastLocation first at CRITICAL:
     *   At 3% battery the OS may refuse getCurrentLocation() entirely.
     *   lastLocation is a cached value — no hardware cost, instant return.
     *   If it's fresh enough (< 5 min), use it directly.
     *   If offline, we increase freshness threshold to 15 min to avoid failing scans that require network.
     *   Only fall back to active scan if lastLocation is stale.
     *
     * Returns true if a point was saved, false if nothing was saved.
     */
    private suspend fun fetchWithBatteryAwareness(
        registration: com.callmap.agenttracker.domain.model.RegistrationResult?
    ): Boolean {
        val tier = getBatteryTier()
        val batteryLevel = getBatteryLevel()
        val isPowerSave = (getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
        val isOffline = !networkObserver.isConnected()
        val highAccuracy = registration?.locationHighAccuracy ?: true

        if (!hasLocationPermission()) {
            logMissedPoint(batteryLevel, tier, "permission_missing")
            return false
        }

        if (!isLocationHardwareEnabled()) {
            val last = runCatching { fusedLocationClient.lastLocation.await() }.getOrNull()
            return if (last != null) {
                persistLocation(last, batteryLevel, "lastLocation_gps_off", allowLenient = true)
            } else {
                logMissedPoint(batteryLevel, tier, "gps_disabled_no_cache")
                false
            }
        }

        if (tier == BatteryTier.DEAD) {
            val last = runCatching { fusedLocationClient.lastLocation.await() }.getOrNull()
            return if (last != null) {
                persistLocation(last, batteryLevel, "lastLocation_dead_battery", allowLenient = true)
            } else {
                logMissedPoint(batteryLevel, tier, "dead_battery_no_fix")
                false
            }
        }

        if (tier == BatteryTier.CRITICAL) {
            val last = runCatching { fusedLocationClient.lastLocation.await() }.getOrNull()
            if (last != null) {
                val ageMs = System.currentTimeMillis() - last.time
                val freshnessThreshold = if (isOffline) 15 * 60 * 1000L else 5 * 60 * 1000L
                if (ageMs < freshnessThreshold) {
                    val source = if (isOffline) "lastLocation_critical_offline" else "lastLocation_critical_battery"
                    if (persistLocation(last, batteryLevel, source, allowLenient = true)) return true
                }
            }
        }

        return performActiveScan(tier, batteryLevel, isPowerSave, isOffline, highAccuracy)
    }

    /**
     * Performs an active location scan with parameters tuned to battery tier.
     *
     * Priority mapping:
     *   NORMAL   → PRIORITY_HIGH_ACCURACY      (GPS chip)
     *   LOW      → PRIORITY_BALANCED_POWER     (WiFi + cell, no GPS chip)
     *   CRITICAL → PRIORITY_LOW_POWER          (cell towers only)
     *
     * Timeout mapping:
     *   NORMAL  → 45s
     *   LOW     → 60s  (network positioning can be slower than GPS)
     *   CRITICAL→ 90s  (cell positioning may take longer at system-throttled state)
     */
    private suspend fun performActiveScan(
        tier: BatteryTier,
        batteryLevel: Int,
        isPowerSave: Boolean,
        isOffline: Boolean,
        highAccuracy: Boolean
    ): Boolean {
        val (priority, timeoutMs) = when (tier) {
            BatteryTier.NORMAL   -> if (highAccuracy) {
                Pair(Priority.PRIORITY_HIGH_ACCURACY, 45_000L)
            } else {
                Pair(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L)
            }
            BatteryTier.LOW      -> Pair(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L)
            BatteryTier.CRITICAL -> Pair(Priority.PRIORITY_LOW_POWER, 90_000L)
            BatteryTier.DEAD     -> return false
        }

        val effectivePriority = if (isPowerSave && tier == BatteryTier.NORMAL && highAccuracy) {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        } else priority

        val fetchLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationService::FetchLock")
        runCatching {
            fetchLock.acquire(timeoutMs + 10_000L)
        }
        return try {
            val cts = CancellationTokenSource()

            var location = withTimeoutOrNull(timeoutMs) {
                fusedLocationClient.getCurrentLocation(effectivePriority, cts.token).await()
            }

            // Fallback chain on timeout or failure
            if (location == null) {
                location = runCatching {
                    fusedLocationClient.lastLocation.await()
                }.getOrNull()
            }

            if (location != null) {
                val source = if (location.provider == null) "active_scan_fallback_$tier" else "active_scan_$tier"
                persistLocation(location, batteryLevel, source, allowLenient = false)
            } else {
                val desperateLast = runCatching { fusedLocationClient.lastLocation.await() }.getOrNull()
                if (desperateLast != null) {
                    persistLocation(
                        desperateLast,
                        batteryLevel,
                        "desperate_fallback_offline_$isOffline",
                        allowLenient = true
                    )
                } else {
                    logMissedPoint(batteryLevel, tier, "no_fix_active_scan_offline_$isOffline")
                    false
                }
            }

        } catch (e: CancellationException) {
            throw e   // propagate — do not swallow

        } catch (e: SecurityException) {
            logMissedPoint(batteryLevel, tier, "permission_revoked")
            false

        } catch (e: Exception) {
            logVerbose("Scan error: ${e.message}")
            false

        } finally {
            if (fetchLock.isHeld) runCatching { fetchLock.release() }
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
        allowLenient: Boolean
    ): Boolean {
        val quality = LocationQualityGate.validate(location, allowLenient)
        if (!quality.accepted) {
            if (!allowLenient) {
                logMissedPoint(battery, getBatteryTier(), "quality_${quality.reason}")
            }
            return false
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
            Log.w(TAG, "Location saved: ($lat,$lon)")
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
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (startId != null) stopSelf(startId) else stopSelf()
    }

    // ── Error handling ────────────────────────────────────────────────────────

    private suspend fun handleSetupError(e: Exception, startId: Int) {
        setupRetryCount++
        Log.e(TAG, "Setup error ($setupRetryCount/$MAX_SETUP_RETRIES): ${e.message}")

        if (setupRetryCount >= MAX_SETUP_RETRIES) {
            setupRetryCount = 0
            val registration = sessionManager.getRegistration().first()
            val inWindow = registration?.trackingEnabled == true &&
                shouldTrackLocationUseCase(System.currentTimeMillis(), registration)
            if (inWindow) {
                logTrackingFailureInWindow(
                    "setup_retry_exhausted",
                    mapOf("error" to (e.message ?: "unknown"))
                )
            }
            trackingState.set(TrackingState.IDLE)
            withContext(Dispatchers.Main) { initiateStop(startId) }
            return
        }

        val backoffMs = 60_000L * setupRetryCount
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
        logVerbose("Missed point: $reason")
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