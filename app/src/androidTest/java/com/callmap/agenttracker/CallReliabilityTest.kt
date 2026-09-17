package com.callmap.agenttracker

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.callmap.agenttracker.data.local.AppDatabase
import com.callmap.agenttracker.data.local.CallCaptureJournal
import com.callmap.agenttracker.data.local.entity.*
import com.callmap.agenttracker.service.CallReceiver
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class CallReliabilityTest {
    private lateinit var context: Context

    @Before fun isolatedEmulatorOnly() {
        assumeTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    private fun call(id: String) = CallLogEntity(id, "test-device", "123", 2, 73,
        "2026-09-17 12:00:00", "2026-09-17 12:01:13", null, recordingAllowed = false)

    @Test fun captureDeduplicatesEvenAfterUploadedRowsAreCleanedUp() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.callLogDao()
            assertTrue(dao.saveCapturedCall(call("same-call")) > 0)
            assertEquals(-1L, dao.saveCapturedCall(call("same-call")))
            val saved = dao.getUnsyncedCallLogs().single()
            assertEquals(73L, saved.callDuration)
            assertFalse(saved.recordingAllowed)
            dao.deleteCallLog(saved)
            assertTrue(dao.exists("same-call"))
            assertEquals(-1L, dao.saveCapturedCall(call("same-call")))
            assertTrue(dao.getUnsyncedCallLogs().isEmpty())
        } finally { db.close() }
    }

    @Test fun pendingAndFailedAudioPathsAreProtectedAndDisabledAudioIsNotRecovered() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.callLogDao()
            dao.saveCapturedCall(call("pending").copy(recordingFilePath = "/pending.wav"))
            dao.saveCapturedCall(call("failed").copy(recordingFilePath = "/failed.wav", syncStatus = SyncStatus.FAILED))
            dao.saveCapturedCall(call("synced").copy(recordingFilePath = "/synced.wav", syncStatus = SyncStatus.SYNCED))
            dao.saveCapturedCall(call("disabled"))
            assertEquals(setOf("/pending.wav", "/failed.wav"), dao.getProtectedRecordingPaths().toSet())
            assertTrue(dao.getLogsMissingRecordings().isEmpty())
        } finally { db.close() }
    }

    @Test fun versionSevenUpgradePreservesQueuesAndCreatesStableRetryIds() = runBlocking {
        val name = "call-reliability-migration-test"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            db.callLogDao().saveCapturedCall(call("existing-call"))
            db.locationDao().insertLocation(LocationEntity(latitude = 1.0, longitude = 2.0, batteryLevel = 50, recordedAt = "2026-09-17 12:00:00"))
            db.deviceEventDao().insertEvent(DeviceEventEntity(deviceUuid = "test-device", eventType = "DEVICE_ONLINE", eventTime = "2026-09-17T12:00:00Z"))
            db.close()
            // Reconstruct the exact v7 schema from v8, preserving real queued rows.
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.execSQL("ALTER TABLE locations DROP COLUMN clientEventId")
                old.execSQL("ALTER TABLE device_events DROP COLUMN clientEventId")
                old.execSQL("ALTER TABLE call_logs DROP COLUMN recordingAllowed")
                old.execSQL("DROP TABLE captured_calls")
                old.execSQL("DROP TABLE room_master_table")
                old.version = 7
            }
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_7_8).build()
            val pointId = db.locationDao().getUnsyncedLocations().single().clientEventId
            val eventId = db.deviceEventDao().getUnsyncedEvents(SyncStatus.PENDING, 10).single().clientEventId
            assertTrue(pointId.isNotEmpty())
            assertTrue(eventId.isNotEmpty())
            assertNotEquals(pointId, eventId)
            assertTrue(db.callLogDao().exists("existing-call"))
            assertEquals(73L, db.callLogDao().getUnsyncedCallLogs().single().callDuration)
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals(pointId, db.locationDao().getUnsyncedLocations().single().clientEventId)
            assertEquals(eventId, db.deviceEventDao().getUnsyncedEvents(SyncStatus.PENDING, 10).single().clientEventId)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun journalRoundTripRestoresInterruptedCallsAndDialMetadata() {
        CallCaptureJournal.begin(context, "isolated-reliability-test")
        val current = CallReceiver.Companion.CallData(number = "123", startTime = 42, wasAnswered = true, recordingAllowed = false)
        CallCaptureJournal.save(context, CallCaptureJournal.Snapshot(current, listOf(current.copy(number = "456")), 2, setOf("processed")))
        CallCaptureJournal.complete(context, current)
        CallCaptureJournal.setDial(context, "789", "{\"test\":true}")
        assertEquals(42L, CallCaptureJournal.read(context).current?.startTime)
        assertFalse(CallCaptureJournal.read(context).current!!.recordingAllowed)
        assertEquals("456", CallCaptureJournal.read(context).interrupted.single().number)
        assertEquals("processed", CallCaptureJournal.read(context).processed.single())
        assertEquals("123", CallCaptureJournal.completed(context).single().number)
        assertEquals("{\"test\":true}", CallCaptureJournal.takeDial(context, "789"))
        assertNull(CallCaptureJournal.takeDial(context, "789"))
        CallCaptureJournal.forgetCompleted(context, 42)
        assertTrue(CallCaptureJournal.completed(context).isEmpty())
    }

    @Test fun missedBroadcastIsRecoveredOfflineWithoutMicrophoneAndOnlyOnce() = runBlocking {
        val session = com.callmap.agenttracker.data.manager.SessionManagerImpl(context)
        val previous = session.getRegistration().first()
        check(previous == null || previous.deviceUuid == RecoveryFixtureTest.TEST_UUID) { "Synthetic registration only" }
        session.saveBaseUrl("https://127.0.0.1:9")
        session.saveRegistration(com.callmap.agenttracker.domain.model.RegistrationResult(
            deviceUuid = RecoveryFixtureTest.TEST_UUID, deviceName = "Recovery test", recordingEnabled = false,
            agentEmail = "recovery@example.invalid", agentName = "Recovery Test User", agentProfile = "",
            trackingEnabled = false, locationFrequency = 300_000, locationOnCall = false
        ))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (permission in listOf("READ_CALL_LOG", "READ_PHONE_STATE")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("pm grant ${context.packageName} android.permission.$permission")
            ).use { it.readBytes() }
        }
        val started = System.currentTimeMillis() - 600_000
        check(context.getSharedPreferences("call_capture_journal", Context.MODE_PRIVATE).edit().putLong("since", started - 1_000).commit())
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_CALL_LOG)
        val uri = try {
            context.contentResolver.insert(android.provider.CallLog.Calls.CONTENT_URI, android.content.ContentValues().apply {
                put(android.provider.CallLog.Calls.NUMBER, "15555550123")
                put(android.provider.CallLog.Calls.DATE, started)
                put(android.provider.CallLog.Calls.DURATION, 73)
                put(android.provider.CallLog.Calls.TYPE, android.provider.CallLog.Calls.OUTGOING_TYPE)
                put(android.provider.CallLog.Calls.NEW, 0)
            })!!
        } finally { automation.dropShellPermissionIdentity() }
        val id = com.callmap.agenttracker.util.CallIdentity.fromSystemLog(RecoveryFixtureTest.TEST_UUID, android.content.ContentUris.parseId(uri), started)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .addMigrations(AppDatabase.MIGRATION_7_8).build()
        suspend fun recover() {
            val work = androidx.work.OneTimeWorkRequestBuilder<com.callmap.agenttracker.data.worker.CallReconciliationWorker>().build()
            val manager = androidx.work.WorkManager.getInstance(context)
            manager.enqueue(work).result.get()
            withTimeout(30_000) {
                while (!manager.getWorkInfoById(work.id).get()!!.state.isFinished) delay(100)
            }
            assertEquals(androidx.work.WorkInfo.State.SUCCEEDED, manager.getWorkInfoById(work.id).get()!!.state)
        }
        try {
            recover()
            val saved = db.callLogDao().getUnsyncedCallLogs().single { it.uniqueId == id }
            assertEquals(73L, saved.callDuration)
            assertFalse(saved.recordingAllowed)
            assertNull(saved.recordingFilePath)
            recover()
            assertEquals(1, db.callLogDao().getUnsyncedCallLogs().count { it.uniqueId == id })
            db.callLogDao().deleteCallLog(saved)
            recover()
            assertFalse(db.callLogDao().getUnsyncedCallLogs().any { it.uniqueId == id })
        } finally {
            db.close()
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_CALL_LOG)
            try {
                context.contentResolver.delete(android.provider.CallLog.Calls.CONTENT_URI,
                    "${android.provider.CallLog.Calls._ID} = ?", arrayOf(android.content.ContentUris.parseId(uri).toString()))
            } finally {
                automation.dropShellPermissionIdentity()
                session.clearSession()
            }
        }
    }
}
