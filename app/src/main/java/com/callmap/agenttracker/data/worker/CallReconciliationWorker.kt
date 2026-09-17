package com.callmap.agenttracker.data.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.UserManager
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.callmap.agenttracker.data.local.CallCaptureJournal
import com.callmap.agenttracker.data.local.entity.CallLogEntity
import com.callmap.agenttracker.domain.manager.DeviceSimManager
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.domain.manager.SyncManager
import com.callmap.agenttracker.domain.repository.CallRepository
import com.callmap.agenttracker.util.CallIdentity
import com.callmap.agenttracker.util.file.FileUtils
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Metadata capture does not require a microphone or a foreground service. */
@HiltWorker
class CallReconciliationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sessionManager: SessionManager,
    private val repository: CallRepository,
    private val deviceSimManager: DeviceSimManager,
    private val syncManager: SyncManager
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (applicationContext.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return Result.retry()
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return Result.retry()
        }
        return try {
            if (applicationContext.getSystemService(android.telephony.TelephonyManager::class.java)?.callState !=
                android.telephony.TelephonyManager.CALL_STATE_IDLE) return Result.retry()
            val registration = sessionManager.getRegistration().first() ?: return Result.success()
            val since = CallCaptureJournal.begin(applicationContext, registration.deviceUuid)
            val snapshot = CallCaptureJournal.read(applicationContext)
            val hints = CallCaptureJournal.completed(applicationContext) + snapshot.interrupted + listOfNotNull(snapshot.current)
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            var recovered = 0
            val columns = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DATE,
                CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME, CallLog.Calls.PHONE_ACCOUNT_ID)
            val cursor = applicationContext.contentResolver.query(CallLog.Calls.CONTENT_URI, columns,
                "${CallLog.Calls.DATE} >= ?", arrayOf(since.toString()), "${CallLog.Calls.DATE} ASC")
                ?: return Result.retry()
            cursor.use {
                while (it.moveToNext()) {
                    val rowId = it.getLong(0)
                    val number = it.getString(1) ?: "Unknown"
                    val started = it.getLong(2)
                    val duration = it.getLong(3).coerceAtLeast(0)
                    val type = it.getInt(4)
                    if (type !in setOf(1, 2, 3, 5, 6, 7)) continue // Exclude voicemail entries.
                    // The system may insert/update the row shortly before the call ends.
                    if (System.currentTimeMillis() - (started + duration * 1000) < 60_000) continue
                    val id = CallIdentity.fromSystemLog(registration.deviceUuid, rowId, started)
                    val hint = hints.filter { call ->
                        abs(call.startTime - started) <= 120_000 &&
                            (call.number == "Unknown" || call.number.filter(Char::isDigit).takeLast(10) == number.filter(Char::isDigit).takeLast(10))
                    }.minByOrNull { call -> abs(call.startTime - started) }
                    if (repository.exists(id)) {
                        hint?.let { call -> CallCaptureJournal.forgetCompleted(applicationContext, call.startTime) }
                        continue
                    }
                    val name = it.getString(5)
                    val slot = deviceSimManager.getSimSlotFromSubscriptionId(it.getString(6))
                    val allowed = (hint?.recordingAllowed ?: registration.recordingEnabled) && registration.recordingEnabled
                    val ended = started + duration * 1000
                    val file = if (allowed && duration > 0) {
                        hint?.let { call -> CallCaptureJournal.recording(applicationContext, "${call.number}|${call.startTime}|${call.type}") }
                            ?.let(::File)?.takeIf { audio -> audio.exists() && audio.length() > 44 }
                            ?: FileUtils.findBestSystemRecording(ended, duration, number, name ?: "Unknown")
                    } else null
                    val call = CallLogEntity(
                        uniqueId = id, deviceUuid = registration.deviceUuid, clientNumber = number,
                        callType = type, callDuration = duration,
                        callStartedAt = format.format(Date(started)), callEndedAt = format.format(Date(ended)),
                        callAnsweredAt = if (duration > 0) format.format(Date(started)) else null,
                        recordingFilePath = file?.absolutePath, recordingAllowed = allowed,
                        callerName = name, wasOnHold = hint?.wasOnHold ?: false,
                        interruptedNumbers = hint?.interruptedBy?.joinToString(",")?.ifEmpty { null },
                        metaData = hint?.metaData ?: CallCaptureJournal.takeDial(applicationContext, number, started),
                        simSlot = slot, deviceSimUuid = slot?.let { deviceSimManager.getSimUuidForSlot(it) },
                        carrierName = slot?.let { deviceSimManager.getCarrierNameForSlot(it) }
                    )
                    repository.saveCallLog(call)
                    hint?.let { call -> CallCaptureJournal.forgetCompleted(applicationContext, call.startTime) }
                    recovered++
                }
            }
            if (recovered > 0) {
                Log.i("CallReconciliation", "Recovered $recovered call metadata records")
                syncManager.triggerPendingSync()
            }
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("CallReconciliation", "Recovery deferred; journal and system logs retained", e)
            Result.retry()
        }
    }

    companion object {
        fun enqueue(context: Context) {
            // No network constraint: saving call metadata must work offline.
            val request = OneTimeWorkRequestBuilder<CallReconciliationWorker>()
                .setInitialDelay(65, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("CallReconciliation", ExistingWorkPolicy.REPLACE, request)
        }

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("CallReconciliationPeriodic",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CallReconciliationWorker>(15, TimeUnit.MINUTES).build())
            enqueue(context)
        }
    }
}
