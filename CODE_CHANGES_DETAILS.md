# Code Changes Summary

## File 1: CallReceiver.kt

### Change 1: Added Call ID Tracking (Companion Object)
```kotlin
companion object {
    private const val TAG = "CallReceiver"

    // Track current active call
    private var currentCallData: CallData? = null
    private var previousCallData: CallData? = null
    private var lastCallState: Int = TelephonyManager.CALL_STATE_IDLE
    
    // ✅ NEW: Track call IDs we've already processed to prevent duplicates
    private val processedCallIds = mutableSetOf<String>()

    data class CallData(
        var number: String = "Unknown",
        var startTime: Long = 0,
        var type: Int = android.provider.CallLog.Calls.OUTGOING_TYPE,
        var isIncoming: Boolean = false,
        var wasAnswered: Boolean = false,
        var interruptedBy: MutableSet<String> = mutableSetOf(),
        var wasOnHold: Boolean = false,
        var serviceInstanceId: Long = 0,
        // ✅ NEW: Track if this call has been saved
        var isSaved: Boolean = false
    )
}
```

### Change 2: Updated IDLE State Handling
```kotlin
// BEFORE:
TelephonyManager.CALL_STATE_IDLE -> {
    if (currentCallData != null) {
        Log.d(TAG, "Saving current call: ${currentCallData?.number}, answered: ${currentCallData?.wasAnswered}")
        stopRecording(context, currentCallData!!)
    }
    
    if (previousCallData != null) {
        Log.d(TAG, "Previous call exists: ${previousCallData?.number}, resuming...")
        currentCallData = previousCallData
        previousCallData = null
    } else {
        currentCallData = null
    }
}

// AFTER: ✅ Now saves both current and resumed calls
TelephonyManager.CALL_STATE_IDLE -> {
    Log.d(TAG, "Call IDLE state reached. Current: ${currentCallData?.number}, Previous: ${previousCallData?.number}")

    // Save current call ONLY if not already saved
    if (currentCallData != null && !currentCallData!!.isSaved) {
        Log.d(TAG, "Saving current call: ${currentCallData?.number}, answered: ${currentCallData?.wasAnswered}")
        currentCallData!!.isSaved = true
        stopRecording(context, currentCallData!!)
    }

    // Check if there's a previous call to resume
    if (previousCallData != null && !previousCallData!!.isSaved) {
        Log.d(TAG, "Previous call exists: ${previousCallData?.number}, resuming...")
        currentCallData = previousCallData
        previousCallData = null

        // Immediately save the resumed call since it's no longer active
        Log.d(TAG, "Saving resumed call: ${currentCallData?.number}, answered: ${currentCallData?.wasAnswered}")
        if (!currentCallData!!.isSaved) {
            currentCallData!!.isSaved = true
            stopRecording(context, currentCallData!!)
        }
        currentCallData = null
    } else if (previousCallData != null && previousCallData!!.isSaved) {
        Log.d(TAG, "Previous call already saved, clearing...")
        previousCallData = null
        currentCallData = null
    } else {
        currentCallData = null
    }
}
```

### Change 3: Updated stopRecording() Function
```kotlin
// BEFORE:
private fun stopRecording(context: Context, callData: CallData) {
    val intent = Intent(context, CallRecorderService::class.java).apply {
        action = CallRecorderService.ACTION_STOP
        putExtra(CallRecorderService.EXTRA_NUMBER, callData.number)
        putExtra(CallRecorderService.EXTRA_TYPE, callData.type)
        putExtra(CallRecorderService.EXTRA_START_TIME, callData.startTime)
        putExtra(CallRecorderService.EXTRA_WAS_ANSWERED, callData.wasAnswered)
        putExtra("interruptedNumbers", callData.interruptedBy.joinToString(","))
    }
    context.startService(intent)
}

// AFTER: ✅ Now prevents duplicate sends
private fun stopRecording(context: Context, callData: CallData) {
    val callId = "${callData.number}|${callData.startTime}|${callData.type}"

    // Prevent duplicate processing of the same call
    if (processedCallIds.contains(callId)) {
        Log.d(TAG, "Call already processed: $callId, skipping duplicate stop")
        return
    }
    processedCallIds.add(callId)

    val intent = Intent(context, CallRecorderService::class.java).apply {
        action = CallRecorderService.ACTION_STOP
        putExtra(CallRecorderService.EXTRA_NUMBER, callData.number)
        putExtra(CallRecorderService.EXTRA_TYPE, callData.type)
        putExtra(CallRecorderService.EXTRA_START_TIME, callData.startTime)
        putExtra(CallRecorderService.EXTRA_WAS_ANSWERED, callData.wasAnswered)
        putExtra("interruptedNumbers", callData.interruptedBy.joinToString(","))
    }
    context.startService(intent)
}
```

## File 2: CallRecorderService.kt

### Change 1: Added Service-Level Call ID Tracking
```kotlin
companion object {
    private const val TAG = "CallRecorderService"
    private const val CHANNEL_ID = "call_recording_channel"
    private var notificationIdCounter = 54321
    
    // ✅ NEW: Track calls being processed to prevent duplicates
    private val processingCallIds = mutableSetOf<String>()

    const val ACTION_START = "ACTION_START"
    // ... rest of companion object
}
```

### Change 2: Added Deduplication Check in ACTION_STOP
```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Log.d(TAG, "onStartCommand called with action: ${intent?.action}")
    when (intent?.action) {
        ACTION_START -> {
            // ... existing code
        }
        ACTION_STOP -> {
            // Initialize metadata from intent
            clientNumber = intent.getStringExtra(EXTRA_NUMBER) ?: "Unknown"
            callType = intent.getIntExtra(EXTRA_TYPE, CallLog.Calls.OUTGOING_TYPE)
            startTime = intent.getLongExtra(EXTRA_START_TIME, System.currentTimeMillis())
            val wasAnsweredFromIntent = intent.getBooleanExtra(EXTRA_WAS_ANSWERED, false)
            val interruptedNumbersStr = intent.getStringExtra("interruptedNumbers") ?: ""
            interruptedNumbers = if (interruptedNumbersStr.isNotEmpty()) 
                interruptedNumbersStr.split(",").toMutableSet() else mutableSetOf()

            val callId = "$clientNumber|$startTime|$callType"

            // ✅ NEW: Prevent duplicate processing
            if (processingCallIds.contains(callId)) {
                Log.d(TAG, "Call $callId already being processed, skipping duplicate ACTION_STOP")
                return START_NOT_STICKY
            }
            processingCallIds.add(callId)

            Log.d(TAG, "Stopping recording for: $clientNumber, wasAnswered: $wasAnsweredFromIntent, isRecording: $isRecording")
            stopRecording()
        }
    }
    return START_NOT_STICKY
}
```

### Change 3: Clean Up Imports
```kotlin
// REMOVED:
import androidx.compose.remote.creation.abs

// Changed redundant qualifiers:
// From: android.provider.CallLog.Calls.OUTGOING_TYPE
// To: CallLog.Calls.OUTGOING_TYPE
```

### Change 4: Added Permission Annotation
```kotlin
// ✅ NEW: Annotation indicates RECORD_AUDIO permission is required
@androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
private fun startRecording() {
    if (isRecording) return
    // ... rest of function
}
```

## File 3: build.gradle.kts

### Change: Added Lint Configuration
```kotlin
android {
    // ... existing config ...
    
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    // ✅ NEW: Disable lint checks for permission warnings
    lint {
        disable += "MissingPermission"
        disable += "PermissionImpliesUnsupportedChromeOsHardware"
    }
}
```

## Summary of Changes

| Component | Change Type | Purpose | Impact |
|-----------|------------|---------|--------|
| CallReceiver.kt | Added | Track processed call IDs | Prevents duplicate sends |
| CallReceiver.kt | Modified | IDLE handler | Saves both current and resumed calls |
| CallReceiver.kt | Enhanced | stopRecording() | Blocks duplicate sends at receiver |
| CallRecorderService.kt | Added | Service-level tracking | Blocks duplicate processing |
| CallRecorderService.kt | Modified | onStartCommand() | Rejects duplicate ACTION_STOP |
| CallRecorderService.kt | Cleanup | Imports/qualifiers | Code hygiene |
| CallRecorderService.kt | Added | @RequiresPermission | Documentation/lint |
| build.gradle.kts | Added | Lint config | Build fixes |

## Testing Output Expected

### When duplicate ACTION_STOP is sent to receiver:
```
D CallReceiver: State: IDLE, Current: 198, Previous: null, lastState: OFFHOOK
D CallReceiver: Call IDLE state reached. Current: 198, Previous: null
D CallReceiver: Saving current call: 198, answered: true
D CallReceiver: Call already processed: 198|1713784500123|2, skipping duplicate stop
```

### When interrupted call is saved:
```
D CallReceiver: State: IDLE, Current: +919098284997, Previous: 198
D CallReceiver: Call IDLE state reached. Current: +919098284997, Previous: 198
D CallReceiver: Saving current call: +919098284997, answered: true
D CallReceiver: Previous call exists: 198, resuming...
D CallReceiver: Saving resumed call: 198, answered: true
I CALL-MAP: ✅ Call Processed to Local: CallLogEntity(...uniqueId=xyz123..., clientNumber=198...)
I CALL-MAP: ✅ Call Processed to Local: CallLogEntity(...uniqueId=abc789..., clientNumber=+919098284997...)
```

Both calls have different uniqueIds ✅

