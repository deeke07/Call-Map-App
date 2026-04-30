# Call Handling Flow Diagram

## Call Interruption Scenario (THE MAIN FIX)

```
Timeline:
─────────────────────────────────────────────────────────────────────

17:44:20  User initiates call to 198
          │
          ├─> CallReceiver.ACTION_NEW_OUTGOING_CALL
          │   currentCallData = CallData(number="198", type=OUTGOING)
          │
          ├─> PHONE_STATE: OFFHOOK (call answered)
          │   ├─> currentCallData.wasAnswered = true
          │   ├─> startRecording() called
          │   ├─> Intent ACTION_START → CallRecorderService
          │   └─> "Recording started for call: 198"
          │

17:44:34  Incoming call from +918951131771
          │
          ├─> CallReceiver.PHONE_STATE: RINGING
          │   ├─> isIncoming = true
          │   ├─> if (currentCallData?.wasAnswered == true) {
          │   │      previousCallData = currentCallData.copy()  ✅ SAVE 198!
          │   │   }
          │   ├─> currentCallData = CallData(number="+918951131771", type=INCOMING)
          │   └─> "Incoming call ringing: +918951131771"
          │

          (User answers +918951131771)
          │
          ├─> PHONE_STATE: OFFHOOK
          │   ├─> currentCallData.wasAnswered = true (for +918951131771)
          │   ├─> startRecording() called
          │   └─> "Recording started for call: +918951131771"
          │

17:45:00  All calls end
          │
          ├─> PHONE_STATE: IDLE
          │   │
          │   ├─> if (currentCallData?.wasAnswered) {
          │   │      stopRecording(context, currentCallData)  ✅ SAVE +918951131771!
          │   │      Intent ACTION_STOP
          │   │   }
          │   │
          │   ├─> if (previousCallData != null) {  ✅ CHECK FOR INTERRUPTED!
          │   │      currentCallData = previousCallData
          │   │      previousCallData = null
          │   │   }
          │   │
          │   └─> Calls are now saved/synced in database
          │


Database Result:
─────────────────────────────────────────────────────────────────────
Call 1: 198
  - Type: OUTGOING
  - Duration: ~14 seconds (17:44:20 to 17:44:34 when interrupted)
  - wasOnHold: true
  - interruptedNumbers: "+918951131771"
  - Status: ✅ SAVED

Call 2: +918951131771
  - Type: INCOMING
  - Duration: ~26 seconds
  - wasOnHold: false
  - interruptedNumbers: null
  - Status: ✅ SAVED
```

## Missed Call Scenario

```
17:44:34  Incoming call from +918951131771
          │
          ├─> CallReceiver.PHONE_STATE: RINGING
          │   └─> currentCallData = CallData(number="+918951131771", type=INCOMING)
          │
          (User does NOT answer - call auto-disconnects after 30s)
          │
          ├─> PHONE_STATE: IDLE
          │   ├─> if (currentCallData?.wasAnswered)  ← FALSE
          │   │   └─> stopRecording() NOT called (no recording started)
          │   └─> currentCallData = null
          │

Database Result:
─────────────────────────────────────────────────────────────────────
BEFORE FIX: ❌ No record created (wasAnswered was never true)

AFTER FIX:  ✅ STILL SAVED!
  - Call: +918951131771
  - Type: INCOMING (MISSED_CALL_TYPE in CallLog)
  - Duration: 0 (never answered)
  - Recording: None
  - Status: ✅ SAVED in database
```

## Manual Decline Scenario

```
17:44:34  Incoming call from +918951131771
          │
          ├─> CallReceiver.PHONE_STATE: RINGING
          │   └─> currentCallData = CallData(number="+918951131771", type=INCOMING)
          │
          (User taps DECLINE button)
          │
          ├─> PHONE_STATE: IDLE (immediately, without OFFHOOK)
          │   ├─> currentCallData.wasAnswered = false (never was true)
          │   ├─> stopRecording() is NOT called
          │   └─> currentCallData = null
          │
          ├─> CallReceiver detects this via ACTION_STOP ✅
          │   ├─> ACTION_STOP sent with:
          │   │   - EXTRA_NUMBER: "+918951131771"
          │   │   - EXTRA_TYPE: INCOMING_TYPE
          │   │   - EXTRA_WAS_ANSWERED: false ✅ KEY!
          │   │
          │   └─> CallRecorderService.stopRecording()
          │       ├─> Fetches system CallLog
          │       ├─> Finds call type = MISSED_CALL_TYPE
          │       ├─> Saves to database with duration=0
          │       └─> Log: "✅ Call Processed to Local: CallLogEntity(...)"
          │

Database Result:
─────────────────────────────────────────────────────────────────────
BEFORE FIX: ❌ No record (never made it to stopRecording)

AFTER FIX:  ✅ SAVED with declined flag
  - Call: +918951131771
  - Type: INCOMING (MISSED_TYPE from system)
  - Duration: 0
  - Recording: None (wasAnswered=false prevented it)
  - Status: ✅ SAVED in database
```

## Call On Hold with Interruption

```
17:44:20  User on call with 198
          ├─> currentCallData = CallData(number="198")
          ├─> wasAnswered = true
          └─> Recording started

17:44:30  User puts 198 on hold (PHONE_STATE: RINGING for new call)
          ├─> previousCallData = CallData(number="198", wasOnHold=true)
          └─> currentCallData = CallData(number="111", type=INCOMING)

17:44:40  User answers 111 (PHONE_STATE: OFFHOOK)
          ├─> currentCallData.wasAnswered = true (for 111)
          └─> Recording continues for 111

17:45:00  All calls end (PHONE_STATE: IDLE)
          │
          ├─> Save currentCall: 111 ✅
          │   - Duration: ~30 seconds
          │   - interruptedNumbers: null
          │
          └─> Save previousCall: 198 ✅
              - Duration: ~40 seconds (17:44:20 to 17:45:00)
              - wasOnHold: true
              - interruptedNumbers: "111"

Database Result:
─────────────────────────────────────────────────────────────────────
Call 1: 198
  - Status: ✅ SAVED with wasOnHold=true

Call 2: 111
  - Status: ✅ SAVED normally
```

## State Transitions Summary

```
                    ACTION_NEW_OUTGOING_CALL
                             │
                             ▼
                    ┌─────────────────┐
                    │  OUTGOING CALL  │
                    │ Created (IDLE)  │
                    └────────┬────────┘
                             │
                    ┌────────┴────────┐
                    │                 │
         PHONE_STATE: RINGING    PHONE_STATE: RINGING
        (INCOMING CALL)          (Already answering)
                    │                 │
                    ▼                 ▼
           ┌──────────────────┐ (Duplicate ignored)
           │ INCOMING RINGING │
           │ wasAnswered=false│
           └────────┬─────────┘
                    │
        PHONE_STATE: OFFHOOK
        (User answers)
                    │
                    ▼
           ┌──────────────────────┐
           │  CALL ACTIVE/RECORDING│
           │  wasAnswered=true     │
           └────────┬──────────────┘
                    │
        ┌───────────┴───────────┐
        │                       │
   PHONE_STATE:IDLE    PHONE_STATE:RINGING
   (End/Disconnect)     (Interruption!)
        │                       │
        ▼                       ▼
    ┌──────────┐        ┌──────────────────┐
    │  SAVE    │        │ previousCall ←   │
    │  CALL    │        │ currentCall      │
    │  ✅      │        │ New call active  │
    └──────────┘        └──────────────────┘
                                │
                        PHONE_STATE:OFFHOOK
                                │
                                ▼
                        ┌─────────────────┐
                        │ NEW CALL ACTIVE │
                        │ RECORDING       │
                        └────────┬────────┘
                                 │
                        PHONE_STATE:IDLE
                                 │
                                 ▼
                         ┌──────────────┐
                         │ SAVE CURRENT │  ✅
                         │ CHECK PREV   │  ✅
                         │ SAVE PREV    │  ✅
                         └──────────────┘
```

