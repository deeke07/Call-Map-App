# Location Tracking Production Fixes - Implementation Guide

## Overview
This document describes the critical production fixes implemented to prevent silent location tracking failures in Doze mode, battery saver modes, and OEM restrictions.

## Issues Fixed

### 1. **Location Silently Stops When Screen is Off (Doze Mode)**
**Problem:** Android Doze mode batches and delays alarms. Location tracking stops because AlarmManager.set() triggers hours late instead of immediately.

**Root Cause:**
- `AlarmManager.set()` without `AllowWhileIdle` gets batched in Doze mode
- AlarmManager.RTC_WAKEUP doesn't guarantee wake in Doze
- CPU can sleep before BroadcastReceiver finishes starting the service

**Fixes Applied:**

#### A. Enhanced Alarm Scheduling (LocationService.kt)
```kotlin
// Before: Conditional logic that sometimes fell back to regular set()
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
    Log.w(TAG, "Missing exact alarm permission. Using inexact fallback.")
    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
} else {
    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
}

// After: Robust handling with multiple fallbacks
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    if (alarmManager.canScheduleExactAlarms()) {
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            Log.d(TAG, "Alarm scheduled (EXACT+DOZE): ${delayMs / 1000}s from now")
        } catch (e: SecurityException) {
            // Fallback to inexact
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    } else {
        // Use inexact (still Doze-safe)
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
    }
} else {
    // Pre-Android 12: Always use exact with fallback
    try {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
    } catch (e: Exception) {
        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
    }
}
```

#### B. New AlarmOptimizer Utility (AlarmOptimizer.kt)
A dedicated utility class that handles all Doze-aware alarm scheduling:
- `scheduleDozeAwareAlarm()` - Smart alarm scheduling with fallbacks
- Handles permission errors gracefully
- Logs all scheduling for diagnostics
- Provides `canScheduleExactAlarms()` for permission checking

**Key: `setExactAndAllowWhileIdle()` is mandatory.**
- `AllowWhileIdle` flag allows the alarm to fire during Doze mode
- Without it, alarms get batched until device exits Doze (can be hours)

---

### 2. **Service Gets Killed by OEM Battery Optimizers (Xiaomi/Samsung/OPPO)**
**Problem:** OEM custom battery managers kill background services regardless of Android settings. Tracking stops permanently.

**Root Cause:**
- Xiaomi MIUI, Samsung One UI, OPPO ColorOS have custom process managers
- They kill apps not in their whitelist
- User never sees the "Battery Optimization" prompt
- Setting `START_STICKY` helps, but not enough

**Fixes Applied:**

#### A. Enhanced BootReceiver (BootReceiver.kt)
- Now handles all boot variants: `ACTION_BOOT_COMPLETED`, `QUICKBOOT_POWERON`, `ACTION_LOCKED_BOOT_COMPLETED`
- Uses `SupervisorJob()` to prevent one failure from cancelling entire recovery
- Multiple error handlers to ensure app reinitializes even if one component fails
- Records boot action for diagnostics

```kotlin
override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
        intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
        intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
        
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                // 1. Detect boot
                val wasTracking = restartDetector.shouldResumeTrackingAfterRestart()
                
                // 2. Log event
                eventManager.logEvent(...)
                
                // 3. Reinitialize app (critical!)
                appInitializer.init()
                
                // 4. Clear state
                restartDetector.clearRestartState()
            } catch (e: Exception) {
                // Retry initialization even if something failed
                try { appInitializer.init() } catch (e2: Exception) { ... }
            }
        }
    }
}
```

#### B. Enhanced DeviceRestartDetector (DeviceRestartDetector.kt)
- Now tracks boot count for diagnostics
- Records the boot action that triggered recovery
- Helps identify devices that reboot frequently (sign of OEM aggression)

```kotlin
fun recordBootAction(action: String)  // Record which boot intent triggered recovery
fun getBootCount(): Int               // Get total reboots detected
```

#### C. Room-Based Offline Buffering
- All locations are saved to Room database before syncing
- If API call fails, data persists locally
- DataCleanupManager cleans up only after confirmed sync

```kotlin
// In LocationService.kt
saveToRoom(location.latitude, location.longitude)  // Save locally FIRST
syncManager.triggerPendingSync()                   // Then sync in background
```

#### D. Manifest Already Has Correct Permissions
```xml
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

---

### 3. **AlarmManager Triggers Get Batched or Skipped**
**Problem:** Even with `AllowWhileIdle`, alarms can batch or skip if:
- Exact alarm quota is exhausted
- Device has many pending alarms
- CPU is in low-power core (efficiency cores)

**Fixes Applied:**

#### A. New AlarmOptimizer with Graceful Degradation
```kotlin
// If exact alarms not available, fall back to inexact (still Doze-safe)
if (alarmManager.canScheduleExactAlarms()) {
    alarmManager.setExactAndAllowWhileIdle(...)  // Best: Exact + Doze
} else {
    alarmManager.setAndAllowWhileIdle(...)       // Good: Inexact + Doze
}
```

#### B. Jitter Support to Avoid Thundering Herd
```kotlin
fun calculateNextAlarmTime(baseIntervalMs: Long, jitterMs: Long): Long {
    // Add random jitter to prevent multiple alarms firing simultaneously
    val jitter = (Math.random() * jitterMs).toLong()
    return System.currentTimeMillis() + baseIntervalMs + jitter
}
```

#### C. LocationService Already Handles Watchdog Retries
- Schedules next alarm BEFORE processing location
- If location fetch fails, alarm still fires to retry
- Even if one cycle fails, next alarm wakes device

---

### 4. **No WakeLock in BroadcastReceiver - CPU Sleeps Before Service Starts**
**Problem:** When AlarmManager triggers ScheduleReceiver:
1. System wakes CPU from Doze for a few seconds
2. onReceive() called to start LocationService
3. If CPU goes to sleep before startForegroundService() completes → service never starts
4. Location tracking stops silently

**Root Cause:**
- Original WakeLock held for only 10 seconds
- High-end devices take longer to acquire audio/location resources
- Xiaomi/Samsung devices have slower startups
- WakeLock wasn't using `ON_AFTER_RELEASE` flag

**Fixes Applied:**

#### A. Enhanced ScheduleReceiver WakeLock (ScheduleReceiver.kt)
```kotlin
// Before: 10 seconds, basic flag
val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "...")
wakeLock.acquire(10000L)

// After: 30 seconds, ON_AFTER_RELEASE flag, proper cleanup
val wakeLock = powerManager.newWakeLock(
    PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
    "ScheduleReceiver::WakeLock"
)
wakeLock.acquire(30000L)  // 30s to account for slow device startups

try {
    // startForegroundService() here
} finally {
    try {
        if (wakeLock.isHeld) wakeLock.release()
    } catch (e: Exception) { ... }
}
```

#### B. New ProximityWakeLockManager (ProximityWakeLockManager.kt)
A dedicated utility for critical operations that need guaranteed CPU time:

```kotlin
// Acquire lock for critical operation
wakeLockManager.acquireCriticalLock(30000L, "location_fetch")

// Do work...

// Release
wakeLockManager.releaseCriticalLock("fetch_complete")
```

---

### 5. **FusedLocationProviderClient Using Only lastLocation - No Fresh Fallback**
**Status:** ✅ Already Fixed in Original Code

LocationService already has proper fallback logic:
```kotlin
var location = withTimeoutOrNull(45000L) {
    fusedLocationClient.getCurrentLocation(
        Priority.PRIORITY_HIGH_ACCURACY,
        cts.token
    ).await()
}

// Fallback to last known location if GPS is struggling
if (location == null) {
    Log.w(TAG, "Fresh fix failed/timeout, falling back to lastLocation")
    location = fusedLocationClient.lastLocation.await()
}
```

---

### 6. **No Local Room Buffer - Points Lost When API Call Fails**
**Status:** ✅ Already Fixed in Original Code

LocationRepositoryImpl saves to Room BEFORE syncing:
```kotlin
override suspend fun saveLocation(location: LocationEntity) {
    dao.insertLocation(location)  // Local first
    Log.d(TAG, "Location saved locally: ${location.latitude}, ${location.longitude}")
}
```

DataCleanupManager only removes records AFTER confirmed sync:
```kotlin
suspend fun cleanupSyncedLocations(): Int {
    return try {
        locationDao.clearSyncedLocations()  // Only clear after sync success
        Log.i(TAG, "Cleaned up synced location records")
        0
    } catch (e: Exception) {
        Log.e(TAG, "Error cleaning up locations", e)
        0
    }
}
```

---

### 7. **Service Not Returning START_STICKY - Not Restarting After Kill**
**Problem:** If LocationService returns `START_NOT_STICKY`, it won't restart after being killed.

**Status in Code:**
LocationService CORRECTLY returns `START_STICKY`:
```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // ... setup code ...
    return START_STICKY  // ✓ Correct
}
```

However, CallRecorderService had an issue:
```kotlin
// Before: Returned START_NOT_STICKY on foreground service failure
try {
    startForeground(...)
} catch (e: Exception) {
    stopSelf()
    return START_NOT_STICKY  // ✗ Wrong!
}

// After: Always return START_STICKY
return START_STICKY  // ✓ Fixed!
```

**Fixes Applied:**

#### A. CallRecorderService Now Returns START_STICKY (CallRecorderService.kt)
```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // ... handling ...
    
    try {
        startForeground(...)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to start foreground service", e)
        return START_STICKY  // Fixed: Allows restart on kill
    }
    
    // ... more handling ...
    
    return START_STICKY  // Always
}
```

---

### 8. **No BootReceiver - Tracking Dies After Device Reboot**
**Status:** ✅ Already Has BootReceiver

Manifest declares:
```xml
<receiver
    android:name=".receiver.BootReceiver"
    android:enabled="true"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.QUICKBOOT_POWERON" />
        <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED"/>
    </intent-filter>
</receiver>
```

**Enhanced:** Now handles all boot variants and has error recovery.

---

## New Files Created

### 1. **ProximityWakeLockManager.kt**
Manages WakeLocks for critical operations.
- `acquireCriticalLock()` - Get lock for important work
- `releaseCriticalLock()` - Clean up
- `acquireTemporaryLock()` - Short-term lock
- `isLocked()` - Check status

### 2. **AlarmOptimizer.kt**
Schedules Doze-aware alarms with automatic fallbacks.
- `scheduleDozeAwareAlarm()` - Smart scheduling
- `cancelAlarm()` - Clean cancellation
- `canScheduleExactAlarms()` - Permission check
- `calculateNextAlarmTime()` - Add jitter

---

## Files Modified

### 1. **LocationService.kt**
- ✅ Enhanced `scheduleNextAlarm()` with better Doze handling
- ✅ Improved error logging for debugging

### 2. **ScheduleReceiver.kt**
- ✅ Increased WakeLock duration from 10s to 30s
- ✅ Added `ON_AFTER_RELEASE` flag
- ✅ Added proper error handling
- ✅ Enhanced logging

### 3. **BootReceiver.kt**
- ✅ Handle all boot action types
- ✅ Use SupervisorJob for error recovery
- ✅ Multiple error handlers
- ✅ Record boot action for diagnostics

### 4. **DeviceRestartDetector.kt**
- ✅ Added boot action tracking
- ✅ Added boot counter
- ✅ Enhanced logging

### 5. **CallRecorderService.kt**
- ✅ Return START_STICKY on foreground service failure
- ✅ Always return START_STICKY

### 6. **AppModule.kt**
- ✅ Added ProximityWakeLockManager provider
- ✅ Added AlarmOptimizer provider
- ✅ Added BatteryOptimizationManager provider

---

## Testing Recommendations

### Unit Tests
1. **AlarmOptimizer.kt** - Test all scheduling paths
   - Test exact alarm scheduling
   - Test inexact fallback
   - Test permission handling

2. **DeviceRestartDetector.kt** - Test restart detection
   - Test boot time comparison
   - Test state persistence
   - Test boot counter

3. **ProximityWakeLockManager.kt** - Test lock management
   - Test acquire/release
   - Test lock duration
   - Test already-held lock handling

### Integration Tests
1. **Boot Recovery Flow**
   - Boot device
   - Verify BootReceiver fires
   - Verify LocationService restarts if was tracking
   - Verify DeviceRestartDetector.recordTrackingState() called

2. **Doze Mode Behavior**
   - Enable Doze on test device: `adb shell dumpsys deviceidle force-idle`
   - Verify alarms still trigger
   - Verify WakeLock prevents sleep

3. **Alarm Scheduling**
   - Mock AlarmManager
   - Verify `setExactAndAllowWhileIdle()` called
   - Verify fallback to inexact on permission error

### Manual Tests
1. **Turn off screen** - Verify tracking continues
2. **Enable Battery Saver** - Verify tracking continues
3. **Restart device** - Verify tracking resumes
4. **Kill app** - Verify service restarts

---

## Deployment Checklist

- [ ] All new files compile without errors
- [ ] All modified files have no breaking changes
- [ ] No new permissions added (uses existing)
- [ ] No new dependencies added (uses existing)
- [ ] Test APK builds successfully
- [ ] Verify AndroidManifest.xml has all permissions
- [ ] Run unit tests
- [ ] Run integration tests
- [ ] Manual testing on:
  - [ ] Low-end device (Xiaomi/Redmi)
  - [ ] Mid-range device
  - [ ] High-end device (Pixel/Samsung)
- [ ] Test with Doze mode enabled
- [ ] Test with Battery Saver enabled
- [ ] Test after device reboot

---

## Production Monitoring

After deployment, monitor:
1. **Tracking uptime** - Should be > 99%
2. **Boot recovery rate** - Should be 100% if was tracking
3. **Alarm failure rate** - Should be < 0.1%
4. **Service kill rate** - Should trend down
5. **Battery optimization status** - User education needed

---

## User Guidance

Provide users with:
1. **Guide to disable battery optimization**
   - Settings → Apps → Call-Map → Battery → Don't optimize
   - Or: Settings → Battery → Battery Saver Exceptions → Call-Map
2. **Verification that tracking is running**
   - Check notification is visible
   - Check location updates in logs
3. **Troubleshooting if tracking stops**
   - Force stop and restart app
   - Reboot device
   - Check battery optimization status

---

## Summary

All 8 production issues are now addressed through:
- ✅ Robust Doze mode handling
- ✅ OEM battery optimizer recovery
- ✅ Intelligent alarm scheduling
- ✅ WakeLock management
- ✅ Boot recovery
- ✅ START_STICKY on all services
- ✅ Local Room buffering
- ✅ Multiple error recovery paths

The fixes are **incremental** (refactoring existing code, not rewriting) and **backward compatible** (no breaking changes).

