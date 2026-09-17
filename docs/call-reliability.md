# Call capture and upload recovery

## Rollout

Deploy the backend migration/API first, then install the Android update. Room 7 → 8
migrates queued calls, events and locations in place; do not uninstall or clear data.
The backend continues accepting older clients without client IDs, but only clients
that send stable IDs receive retry deduplication.

## Behavior

- Recording disabled: metadata is recovered from Android CallLog and queued locally;
  no microphone service is started and no audio is attached or recovered for that call.
- Phone-state broadcasts use `goAsync`, a serialized handler and a committed journal.
  Active/interrupted calls, processed IDs, recording paths and FCM dial metadata are
  retained across process death. Explicit logout resets the capture window.
- A network-independent reconciliation worker runs after phone events, initialization,
  and periodically. It waits for completed calls and shares a stable identity with
  live capture. A durable capture ledger survives removal of uploaded call rows.
- If the OS rejects a microphone foreground service, the journal and system CallLog
  still drive metadata capture. This does not bypass Android microphone restrictions
  or promise that audio exists on every OS/OEM. READ_CALL_LOG remains required.
- Recovery starts at the current registration/upgrade capture baseline, not arbitrary
  historical calls from before monitoring. CallLog recovery requires that the OS row
  still exists; users/OEMs deleting their system call history can prevent reconciliation.
- Unuploaded audio is never deleted merely because it is old. Automatic cleanup only
  deletes app-owned audio after server confirmation and checks other pending references.
  Unattributed orphan files are conservatively retained for recovery, so disk usage
  should be monitored during very long outages.
- Every queued point/event has a persisted client ID. Calls use their stable local ID.
  The server enforces device-scoped unique constraints; lost responses can be retried.
- Remote `device_status` logout and FCM settings refresh remain enabled. First-unlock
  protection remains in place. Connectivity alone is not a logout signal.

## Verification

Focused JVM tests: `CallIdentityTest`, `CallMapperTest`, `UserUnlockGateTest`,
`StartupDestinationTest`. `CallReliabilityTest` is emulator-only and checks Room
migration preservation, durable capture deduplication, protected pending audio paths,
and journal persistence. Never run the registration fixture on a physical phone.

Manual matrix (Android 14–16 and representative OEMs): recording on/off; answered,
missed, rejected and blocked calls; process death during RINGING/OFFHOOK/IDLE; reboot;
offline for >24h; internet returning after boot; server response lost after commit;
microphone foreground-service start denied. Check both metadata and optional audio,
and verify that retry/recovery creates exactly one backend record per client ID.
