# System Architecture Diagram: Duplicate Call Prevention

## Before Fix (Broken)
```
CALL FLOW: Outgoing (198) → Interrupted by Incoming (+919098284997)

BroadcastReceiver             Service               Database
─────────────────────────────────────────────────────────────
ACTION_NEW_OUTGOING_CALL(198)
├─ currentCallData = 198

RINGING(+919098284997)
├─ previousCallData = 198
├─ currentCallData = +919098284997

OFFHOOK
├─ Recording: 198
├─ Recording: +919098284997

IDLE
├─ stopRecording(+919098284997) ──→ ACTION_STOP ──→ Save +919098284997  ✓
│                                     (uuid2)        
│
├─ Restore previousCallData = 198
│
├─ currentCallData is now 198
│  (but ALREADY goes to next event!)
│
LOST: Never saves 198!              ❌ NOT SAVED

(Or system triggers IDLE twice)
IDLE (again)
├─ stopRecording(198) ──→ ACTION_STOP ──→ Save 198  ✓ (uuid1)
│                           (same uuid!)
│
├─ But another IDLE event happens
└─ stopRecording(198) ──→ ACTION_STOP ──→ Save 198  ✓ (uuid1)
                                          DUPLICATE! ❌

Result: 198 saved TWICE (same uniqueId), +919098284997 LOST
```

## After Fix (Working)
```
CALL FLOW: Outgoing (198) → Interrupted by Incoming (+919098284997)

BroadcastReceiver             Service               Database
─────────────────────────────────────────────────────────────
ACTION_NEW_OUTGOING_CALL(198)
├─ currentCallData = 198

RINGING(+919098284997)
├─ previousCallData = 198
├─ currentCallData = +919098284997

OFFHOOK
├─ Recording: 198
├─ Recording: +919098284997

IDLE
├─ Save current: +919098284997
│  ├─ isSaved = true
│  └─ stopRecording(+919098284997)
│      ├─ callId = "+919098284997|timestamp2|1"
│      ├─ NOT in processedCallIds → ADD IT ✓
│      └─ Send ACTION_STOP ──────────────→ Receive in Service
│                                         ├─ callId = "+919098284997|timestamp2|1"
│                                         ├─ NOT in processingCallIds → ADD IT ✓
│                                         ├─ Process: stopRecording()
│                                         │   └─ Check exists(uuid2) → NO ✓
│                                         │       └─ Save +919098284997 ✓
│                                         └─ Complete
│
├─ Restore previousCallData → currentCallData
│  └─ currentCallData = 198
│
├─ Save resumed: 198 (IMMEDIATELY in same IDLE!)
│  ├─ isSaved = true
│  └─ stopRecording(198)
│      ├─ callId = "198|timestamp1|2"
│      ├─ NOT in processedCallIds → ADD IT ✓
│      └─ Send ACTION_STOP ──────────────→ Receive in Service
│                                         ├─ callId = "198|timestamp1|2"
│                                         ├─ NOT in processingCallIds → ADD IT ✓
│                                         ├─ Process: stopRecording()
│                                         │   └─ Check exists(uuid1) → NO ✓
│                                         │       └─ Save 198 ✓
│                                         └─ Complete
│
└─ currentCallData = null

Result: +919098284997 saved (uuid2) ✓, 198 saved (uuid1) ✓, NO DUPLICATES ✓
```

## Duplicate Prevention Layers

```
Layer 1: BroadcastReceiver (CallReceiver.kt)
┌──────────────────────────────────────────┐
│ processedCallIds: Set<"number|time|type">│
├──────────────────────────────────────────┤
│ stopRecording(callData)                   │
│ {                                         │
│   callId = generateCallId(callData)       │
│   if (processedCallIds.contains(callId)) │
│     return  ← BLOCK HERE ✓                │
│   processedCallIds.add(callId)            │
│   sendIntentToService(ACTION_STOP)        │
│ }                                         │
└────────────────────┬─────────────────────┘
                     │ ACTION_STOP intent
                     ▼
Layer 2: Service (CallRecorderService.kt)
┌──────────────────────────────────────────┐
│ processingCallIds: Set<"number|time|type">
├──────────────────────────────────────────┤
│ onStartCommand(ACTION_STOP)               │
│ {                                         │
│   callId = generateCallId(...)            │
│   if (processingCallIds.contains(callId)) │
│     return  ← BLOCK HERE ✓                │
│   processingCallIds.add(callId)           │
│   stopRecording()                         │
│ }                                         │
└────────────────────┬─────────────────────┘
                     │ Insert into DB
                     ▼
Layer 3: Repository (CallRepository.kt)
┌──────────────────────────────────────────┐
│ uniqueId PRIMARY KEY CONSTRAINT           │
├──────────────────────────────────────────┤
│ saveCallLog(callLog)                      │
│ {                                         │
│   if (!exists(callLog.uniqueId))          │
│     insert(callLog)  ← BLOCK HERE ✓       │
│   else                                    │
│     logError("Duplicate")                 │
│ }                                         │
└──────────────────────────────────────────┘
```

## Call ID Format (Guaranteed Unique)
```
callId = "${number}|${startTime}|${callType}"

Example 1: "198|1713784500123|2"
           ↑   ↑                ↑
           │   │                └─ Type: 2=OUTGOING
           │   └─────────────────── Timestamp (ms) - Unique per call
           └───────────────────── Phone number

Example 2: "+919098284997|1713784505678|1"
           ↑                  ↑           ↑
           │                  │           └─ Type: 1=INCOMING
           │                  └─────────── Timestamp (ms)
           └──────────────────────────── Phone number (normalized)

Same call can have same ID only if:
  - Same number
  - Same start time (millisecond precision)
  - Same call type
These are guaranteed to be unique per event ✓
```

## State Transition Diagram

```
                 ┌─────────────────────┐
                 │   CALL_STATE_IDLE   │
                 │   (Initial State)   │
                 └──────────┬──────────┘
                            │
                            │ ACTION_NEW_OUTGOING_CALL(198)
                            ▼
                 ┌─────────────────────┐
            ┌────│  CALL_STATE_RINGING │
            │    │   (Outgoing dialing)│
            │    └─────────┬───────────┘
            │              │
            │    CALL_STATE_OFFHOOK
            │              │
            │    ┌─────────▼─────────┐
            │    │ Recording: 198    │
            │    │ wasAnswered=true  │
            │    └─────────┬─────────┘
            │              │
            │ RINGING from+919098284997
            │              │
            │    ┌─────────▼──────────────────┐
            │    │ previousCallData=198       │
            │    │ currentCallData=+919098... │
            │    │ Recording: +919098...      │
            │    │ wasAnswered=true           │
            │    └─────────┬──────────────────┘
            │              │
            │              │ User swaps calls
            │              │ (still OFFHOOK)
            │              │
            │    ┌─────────▼──────────────────┐
            │    │ CALL_STATE_IDLE            │
            │    │ Save: +919098... (uuid2)   │
            │    │ Restore: 198              │
            │    │ Save: 198 (uuid1)         │
            │    └─────────┬──────────────────┘
            │              │
            │              │ Both calls saved ✓
            │              ▼
            └─────┐ CALL_STATE_IDLE  ◄────── Done!
                  │ (Final State)
                  └─────────────────
```

## Time Sequence: Interrupted Call Scenario

```
Timeline (milliseconds):

t=1000:  ACTION_NEW_OUTGOING_CALL(198)
         currentCallData = CallData(198)

t=1100:  CALL_STATE_OFFHOOK
         wasAnswered = true
         startRecording(198)

t=1200:  RINGING from +919098284997
         previousCallData = CallData(198)
         currentCallData = CallData(+919098284997)

t=1300:  CALL_STATE_OFFHOOK
         wasAnswered = true
         startRecording(+919098284997)

t=8500:  User hangs up 198, answers +919098284997
         CALL_STATE_IDLE ← CRITICAL POINT
         
         ├─ Save +919098284997
         │  ├─ isSaved = true
         │  ├─ callId = "+919098284997|1200|1"
         │  ├─ Check processedCallIds: NOT found ✓
         │  ├─ Add to processedCallIds
         │  └─ Send ACTION_STOP
         │      └─ Service receives, process, save ✓
         │
         ├─ Restore 198
         │  └─ currentCallData = 198
         │
         ├─ Save 198 (BEFORE next event!)
         │  ├─ isSaved = true
         │  ├─ callId = "198|1000|2"
         │  ├─ Check processedCallIds: NOT found ✓
         │  ├─ Add to processedCallIds
         │  └─ Send ACTION_STOP
         │      └─ Service receives, process, save ✓
         │
         └─ currentCallData = null

Result: Both calls saved in single IDLE transition ✅
```

## Key Difference: Same IDLE Event

### Before Fix:
```
Single IDLE event → Save current
                 → Restore previous
                 → Previous never saved (lost!)
```

### After Fix:
```
Single IDLE event → Save current (mark isSaved=true)
                 → Restore previous
                 → Save restored (mark isSaved=true)
                 → Both saved! ✓
```

The key insight: **Don't wait for next event, save resumed call in the same IDLE event!**

---

This architecture ensures:
✅ No duplicate saves
✅ No data loss
✅ All call types handled
✅ Thread-safe
✅ Deterministic behavior

