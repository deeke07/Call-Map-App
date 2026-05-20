package com.callmap.agenttracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.callmap.agenttracker.data.local.entity.CallLogEntity
import com.callmap.agenttracker.data.local.entity.SyncStatus
import com.callmap.agenttracker.data.worker.CallSyncWorker
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.domain.repository.CallRepository
import com.callmap.agenttracker.util.audio.VoiceEnhancer
import com.callmap.agenttracker.util.file.FileUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.math.abs

@AndroidEntryPoint
class CallRecorderService : Service() {

    @Inject
    lateinit var callRepository: CallRepository

    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var networkObserver: com.callmap.agenttracker.util.NetworkObserver

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient

    // State for the single active internal recording
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile private var isRecording = false
    @Volatile private var recordingFile: File? = null
    @Volatile private var recordingJob: Job? = null
    @Volatile private var activeRecordingCallId: String? = null

    // For handling preemption and thread-safe state
    private val recordingMutex = Mutex()

    // Track active processing tasks to know when to stopSelf()
    private val activeTasks = AtomicInteger(0)

    companion object {
        private const val TAG = "CallRecorderService"
        private const val CHANNEL_ID = "call_recording_channel"
        // Track calls being processed to prevent duplicates across service lifecycle
        private val processingCallIds = Collections.synchronizedSet(mutableSetOf<String>())
        // Preserve preempted files across service restarts
        private val pendingInternalFiles = Collections.synchronizedMap(mutableMapOf<String, File>())
        // Preserve per-call audio diagnostics so STOP/finalize can report verdict after joins/preemption.
        private val pendingCaptureDiagnostics = Collections.synchronizedMap(mutableMapOf<String, CaptureDiagnostics>())

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"

        const val EXTRA_NUMBER = "EXTRA_NUMBER"
        const val EXTRA_TYPE = "EXTRA_TYPE"
        const val EXTRA_START_TIME = "EXTRA_START_TIME"
        const val EXTRA_ANSWERED_TIME = "EXTRA_ANSWERED_TIME"
        const val EXTRA_WAS_ANSWERED = "EXTRA_WAS_ANSWERED"
        const val EXTRA_WAS_ON_HOLD = "EXTRA_WAS_ON_HOLD"
        const val EXTRA_INTERRUPTED_NUMBERS = "EXTRA_INTERRUPTED_NUMBERS"
        const val EXTRA_RECORDING_ENABLED = "EXTRA_RECORDING_ENABLED"
        const val EXTRA_META_DATA = "EXTRA_META_DATA"

        private const val SAMPLE_RATE = 8000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val PROBE_PEAK_THRESHOLD = 20
        private const val SIGNAL_PEAK_THRESHOLD = 80
        private const val SOURCE_PROBE_WINDOW_MS = 2500L
    }

    private data class CaptureDiagnostics(
        val source: Int,
        val sourceName: String,
        val recordedSeconds: Double,
        val zeroRatio: Double,
        val strongSignalRatio: Double,
        val maxPeak: Int,
        val verdict: String,
        val reason: String
    ) {
        fun toCompactFlag(): String = String.format(
            Locale.US,
            "captureVerdict=%s;source=%s;sec=%.1f;zero=%.2f;strong=%.2f;maxPeak=%d",
            verdict,
            sourceName,
            recordedSeconds,
            zeroRatio,
            strongSignalRatio,
            maxPeak
        )

        fun toLogLine(): String = "$verdict via $sourceName (sec=${"%.1f".format(Locale.US, recordedSeconds)}, zero=${"%.2f".format(Locale.US, zeroRatio)}, strong=${"%.2f".format(Locale.US, strongSignalRatio)}, maxPeak=$maxPeak, reason=$reason)"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.d(TAG, "Service restarted with null intent. Checking if we should stop.")
            decrementAndCheckStop(startId)
            return START_STICKY
        }

        val action = intent.action
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: "Unknown"
        val type = intent.getIntExtra(EXTRA_TYPE, CallLog.Calls.OUTGOING_TYPE)
        val startTime = intent.getLongExtra(EXTRA_START_TIME, System.currentTimeMillis())
        val answeredTime = intent.getLongExtra(EXTRA_ANSWERED_TIME, 0L)
        val metaData = intent.getStringExtra(EXTRA_META_DATA)
        val callId = "$number|$startTime|$type"

        Log.d(TAG, "onStartCommand: action=$action, callId=$callId")

        // Ensure service is foregrounded immediately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(54321, createNotification("Processing call with $number"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(54321, createNotification("Processing call with $number"))
        }

        when (action) {
            ACTION_START -> {
                activeTasks.incrementAndGet()
                serviceScope.launch {
                    try {
                        val registration = sessionManager.getRegistration().first()
                        val recordingEnabled = registration?.recordingEnabled ?: true
                        
                        recordingMutex.withLock {
                            if (activeRecordingCallId != null && activeRecordingCallId != callId) {
                                Log.i(TAG, "Preempting recording for $activeRecordingCallId to start $callId")
                                stopInternalRecordingLocked()
                            }

                            if (activeRecordingCallId == null && recordingEnabled) {
                                activeRecordingCallId = callId
                                startRecordingInternal(number, callId)
                            } else {
                                Log.d(TAG, "Recording not started for $callId (Enabled: $recordingEnabled)")
                            }
                        }
                    } finally {
                        decrementAndCheckStop(startId)
                    }
                }
            }
            ACTION_STOP -> {
                val wasAnswered = intent.getBooleanExtra(EXTRA_WAS_ANSWERED, false)
                val wasOnHold = intent.getBooleanExtra(EXTRA_WAS_ON_HOLD, false)
                val interrupted = intent.getStringExtra(EXTRA_INTERRUPTED_NUMBERS) ?: ""

                activeTasks.incrementAndGet()

                serviceScope.launch {
                    try {
                        var fileToProcess: File? = null
                        var jobToJoin: Job? = null

                        recordingMutex.withLock {
                            if (callId == activeRecordingCallId) {
                                Log.d(TAG, "Stopping active internal recording for $callId")
                                isRecording = false
                                jobToJoin = recordingJob
                                fileToProcess = recordingFile

                                activeRecordingCallId = null
                                recordingFile = null
                                recordingJob = null
                            } else {
                                fileToProcess = pendingInternalFiles.remove(callId)
                                if (fileToProcess != null) {
                                    Log.d(TAG, "Found preempted file for $callId")
                                }
                            }
                        }

                        jobToJoin?.join()

                        finalizeCallRecord(
                            number = number,
                            type = type,
                            startTime = startTime,
                            answeredTime = answeredTime,
                            endTime = System.currentTimeMillis(),
                            wasAnswered = wasAnswered,
                            wasOnHold = wasOnHold,
                            interruptedNumbers = interrupted,
                            internalFile = fileToProcess,
                            metaData = metaData,
                            startId = startId
                        )
                    } finally {
                        decrementAndCheckStop(startId)
                    }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun stopInternalRecordingLocked() {
        val oldId = activeRecordingCallId ?: return
        isRecording = false
        recordingJob?.join()

        val file = recordingFile
        if (file != null && file.exists() && file.length() > 44) {
            pendingInternalFiles[oldId] = file
            Log.d(TAG, "Saved preempted recording for $oldId to pending map")
        }

        activeRecordingCallId = null
        recordingFile = null
        recordingJob = null
    }

    private fun decrementAndCheckStop(startId: Int) {
        val remaining = activeTasks.decrementAndGet()
        val activeId = activeRecordingCallId
        if (remaining == 0 && activeId == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private fun startRecordingInternal(number: String, callId: String) {
        Log.d(TAG, "Internal recording request for $number")

        recordingJob = serviceScope.launch {
            var localAudioRecord: AudioRecord? = null
            var localFos: FileOutputStream? = null
            var localFile: File? = null
            var localTotalLen = 0L
            var originalMode = AudioManager.MODE_NORMAL
            var chosenSource = -1
            var totalReadBuffers = 0L
            var zeroReadBuffers = 0L
            var strongSignalBuffers = 0L
            var maxPeakObserved = 0
            var fallbackRecord: AudioRecord? = null
            var fallbackSource = -1
            var fallbackMaxPeak = 0

            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                originalMode = audioManager.mode
                
                // Attempting to set mode to MODE_IN_COMMUNICATION to prioritize audio capture
                try {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    Log.d(TAG, "AudioManager mode set to MODE_IN_COMMUNICATION")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set AudioManager mode: ${e.message}")
                }

                // Give the system more time to release hardware from the dialer's initial lock
                delay(1200)

                isRecording = true

                val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                if (bufferSize <= 0) {
                    Log.e(TAG, "Invalid buffer size: $bufferSize")
                    isRecording = false
                    return@launch
                }

                // Privileged telephony sources are blocked on most non-system builds.
                // Keep app-accessible sources and prioritize the best chance for bidirectional capture.
                val sourceOptions = listOf(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.UNPROCESSED,
                    MediaRecorder.AudioSource.CAMCORDER,
                    MediaRecorder.AudioSource.MIC
                )

                val fileName = "call_${System.currentTimeMillis()}.wav"
                localFile = FileUtils.getRecordingFile(applicationContext, fileName)
                recordingFile = localFile
                localFos = FileOutputStream(localFile)
                localFos.write(ByteArray(44)) // Dummy header

                val audioBuffer = ShortArray(bufferSize)
                val enhancer = VoiceEnhancer(
                    sampleRate = SAMPLE_RATE,
                    targetRms = 0.82f,
                    minGain = 1.25f,
                    maxGain = 1200.0f,
                    extraQuietBoost = 2.0f
                )
                var zeroCounter = 0

                for (source in sourceOptions) {
                    if (!isRecording || !isActive) break
                    
                    Log.d(TAG, "Attempting source: $source")
                    try {
                        val tempRecord = AudioRecord.Builder()
                            .setAudioSource(source)
                            .setAudioFormat(AudioFormat.Builder()
                                .setEncoding(AUDIO_FORMAT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(CHANNEL_CONFIG)
                                .build())
                            .setBufferSizeInBytes(bufferSize)
                            .build()

                        if (tempRecord.state != AudioRecord.STATE_INITIALIZED) {
                            tempRecord.release()
                            continue
                        }

                        tempRecord.startRecording()
                        if (tempRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            tempRecord.release()
                            continue
                        }

                        // Probe each source before locking in. We accept strong-signal sources first,
                        // but keep VOICE_COMMUNICATION even with low initial energy to avoid false fallback.
                        var sourceYieldedData = false
                        var sourceReadable = false
                        var maxPeak = 0
                        val startTime = System.currentTimeMillis()
                        while (System.currentTimeMillis() - startTime < SOURCE_PROBE_WINDOW_MS && isRecording && isActive) {
                            val read = tempRecord.read(audioBuffer, 0, bufferSize)
                            if (read > 0) {
                                sourceReadable = true
                                var peak = 0
                                for (i in 0 until read) {
                                    val absSample = abs(audioBuffer[i].toInt())
                                    if (absSample > peak) peak = absSample
                                }
                                if (peak > maxPeak) maxPeak = peak

                                if (peak >= PROBE_PEAK_THRESHOLD) {
                                    sourceYieldedData = true
                                    Log.i(TAG, "Source $source yielded audio! Locking in.")
                                    // Process and write this first chunk
                                    enhancer.process(audioBuffer, read)
                                    val byteBuffer = ByteArray(read * 2)
                                    for (i in 0 until read) {
                                        val sample = audioBuffer[i].toInt()
                                        byteBuffer[i * 2] = (sample and 0xff).toByte()
                                        byteBuffer[i * 2 + 1] = ((sample shr 8) and 0xff).toByte()
                                    }
                                    localFos.write(byteBuffer)
                                    localTotalLen += byteBuffer.size
                                    break
                                }
                            } else if (read < 0) break
                        }

                        val shouldLockSource = sourceYieldedData

                        if (shouldLockSource) {
                            fallbackRecord?.let {
                                try {
                                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                                } catch (_: Exception) {}
                                it.release()
                            }

                            localAudioRecord = tempRecord
                            audioRecord = tempRecord
                            chosenSource = source

                            // Try to disable processing if possible on the successful record
                            if (NoiseSuppressor.isAvailable()) {
                                try {
                                    noiseSuppressor = NoiseSuppressor.create(tempRecord.audioSessionId)
                                    noiseSuppressor?.enabled = false
                                } catch (e: Exception) {
                                    Log.w(TAG, "Could not configure NoiseSuppressor: ${e.message}")
                                }
                            }
                            if (AcousticEchoCanceler.isAvailable()) {
                                try {
                                    echoCanceler = AcousticEchoCanceler.create(tempRecord.audioSessionId)
                                    echoCanceler?.enabled = false
                                } catch (e: Exception) {
                                    Log.w(TAG, "Could not configure AcousticEchoCanceler: ${e.message}")
                                }
                            }

                            break // Exit source loop, we found our winner
                        } else if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION && sourceReadable) {
                            // Keep VOICE_COMMUNICATION only as fallback; continue searching for a stronger source.
                            fallbackRecord?.let {
                                try {
                                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                                } catch (_: Exception) {}
                                it.release()
                            }
                            fallbackRecord = tempRecord
                            fallbackSource = source
                            fallbackMaxPeak = maxPeak
                            Log.w(TAG, "Keeping weak VOICE_COMMUNICATION as fallback (maxPeak=$maxPeak), testing next sources...")
                        } else {
                            Log.w(TAG, "Source $source did not produce usable audio (maxPeak=$maxPeak), trying next...")
                            try {
                                tempRecord.stop()
                            } catch (_: Exception) {}
                            tempRecord.release()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to test source $source: ${e.message}")
                    }
                }

                if (localAudioRecord == null && fallbackRecord != null) {
                    localAudioRecord = fallbackRecord
                    audioRecord = fallbackRecord
                    chosenSource = fallbackSource
                    fallbackRecord = null
                    Log.w(TAG, "Using fallback VOICE_COMMUNICATION source (maxPeak=$fallbackMaxPeak).")
                }

                val record = localAudioRecord
                if (record == null) {
                    Log.e(TAG, "All sources failed or returned silence.")
                    isRecording = false
                    return@launch
                }

                // Main recording loop for the chosen source
                while (isRecording && isActive) {
                    val read = record.read(audioBuffer, 0, bufferSize)
                    if (read > 0) {
                        totalReadBuffers++

                        // Check if we are getting only zeros (hardware block)
                        var isSilent = true
                        var peak = 0
                        for (i in 0 until read) {
                            val sampleAbs = abs(audioBuffer[i].toInt())
                            if (sampleAbs > peak) peak = sampleAbs
                            if (audioBuffer[i] != 0.toShort()) {
                                isSilent = false
                            }
                        }
                        if (peak > maxPeakObserved) maxPeakObserved = peak
                        if (peak >= SIGNAL_PEAK_THRESHOLD) strongSignalBuffers++

                        if (isSilent) {
                            zeroReadBuffers++
                            zeroCounter++
                            if (zeroCounter % 50 == 0) {
                                Log.w(TAG, "Warning: Receiving ONLY zeros from AudioRecord ($chosenSource). Still zero after ${zeroCounter * (bufferSize.toFloat() / SAMPLE_RATE)} seconds.")
                            }
                        } else {
                            if (zeroCounter > 0) Log.i(TAG, "Signal regained after $zeroCounter zero-buffers.")
                            zeroCounter = 0
                        }

                        // Apply dynamic enhancement
                        enhancer.process(audioBuffer, read)

                        val byteBuffer = ByteArray(read * 2)
                        for (i in 0 until read) {
                            val sample = audioBuffer[i].toInt()
                            byteBuffer[i * 2] = (sample and 0xff).toByte()
                            byteBuffer[i * 2 + 1] = ((sample shr 8) and 0xff).toByte()
                        }
                        localFos.write(byteBuffer)
                        localTotalLen += byteBuffer.size
                    } else if (read < 0) {
                        Log.w(TAG, "AudioRecord.read() returned error: $read")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in recording loop: ${e.message}", e)
            } finally {
                isRecording = false
                
                // Restore original audio mode
                try {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.mode = originalMode
                    Log.d(TAG, "AudioManager mode restored to $originalMode")
                } catch (e: Exception) { }

                try {
                    localFos?.close()
                    if (localTotalLen > 0 && localFile != null) {
                        updateWavHeader(localFile, localTotalLen)
                        Log.d(TAG, "Recording file finalized: ${localFile.absolutePath} ($localTotalLen bytes)")
                    } else {
                        localFile?.delete()
                        Log.d(TAG, "Recording file deleted (no data): ${localFile?.name}")
                        if (localFile == recordingFile) recordingFile = null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing recording resources", e)
                }

                val diagnostics = buildCaptureDiagnostics(
                    source = chosenSource,
                    recordedBytes = localTotalLen,
                    totalReadBuffers = totalReadBuffers,
                    zeroReadBuffers = zeroReadBuffers,
                    strongSignalBuffers = strongSignalBuffers,
                    maxPeakObserved = maxPeakObserved
                )
                pendingCaptureDiagnostics[callId] = diagnostics
                Log.i(TAG, "Capture diagnostic [$callId]: ${diagnostics.toLogLine()}")

                noiseSuppressor?.release()
                noiseSuppressor = null
                echoCanceler?.release()
                echoCanceler = null

                localAudioRecord?.apply {
                    try {
                        if (state == AudioRecord.STATE_INITIALIZED && recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            stop()
                        }
                    } catch (e: Exception) {}
                    release()
                }
                if (localAudioRecord == audioRecord) audioRecord = null

                fallbackRecord?.let {
                    try {
                        if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                    } catch (_: Exception) {}
                    it.release()
                }
            }
        }
    }

    private suspend fun finalizeCallRecord(
        number: String,
        type: Int,
        startTime: Long,
        answeredTime: Long,
        endTime: Long,
        wasAnswered: Boolean,
        wasOnHold: Boolean,
        interruptedNumbers: String,
        internalFile: File?,
        metaData: String? = null,
        startId: Int
    ) {
        val callId = "$number|$startTime|$type"
        val captureDiagnostics = pendingCaptureDiagnostics.remove(callId)
        if (processingCallIds.contains(callId)) {
            Log.d(TAG, "Already processing $callId, skipping")
            return
        }
        processingCallIds.add(callId)
        // Cleanup old IDs
        if (processingCallIds.size > 100) {
            val toRemove = processingCallIds.take(50)
            processingCallIds.removeAll(toRemove.toSet())
        }

        // 1. Initial delay to allow System CallLog to be written
        // Reduced delay for faster processing, but kept enough for the OS to commit the log
        delay(800)

        val registration = sessionManager.getRegistration().first() ?: return
        val batteryLevel = getBatteryLevel()
        val apkVersion = getApkVersion()

        // Only fetch location if trackingEnabled AND locationOnCall are both true
        val location = if (registration.locationOnCall) getCurrentLocation() else null

        // 2. Fetch CallLog (Retry up to 20s)
        val callLogDetails = getSystemCallLogDetails(number, startTime, type)

        val finalCallerName = callLogDetails?.name
        val finalNumber = callLogDetails?.number ?: number
        val systemDuration = callLogDetails?.duration ?: 0L
        val logStartTime = callLogDetails?.timestamp ?: startTime

        val internalFileValid = internalFile?.exists() == true && FileUtils.getAudioDuration(internalFile) > 0
        val internalFileDuration = if (internalFileValid) FileUtils.getAudioDuration(internalFile) else 0L

        // Source of truth for answered status and type
        val finalType = callLogDetails?.type ?: type

        // Picked up means the receiver actually answered (talk time > 0)
        val wasActuallyPickedUp = if (systemDuration > 0) {
            true
        } else if (callLogDetails == null) {
            // Fallback for incoming calls if system log failed to fetch
            wasAnswered && finalType == CallLog.Calls.INCOMING_TYPE
        } else {
            false
        }

        val logEndTime = if (systemDuration > 0) logStartTime + (systemDuration * 1000) else endTime

        var finalRecordingPath: String? = null
        var isMismatch = false

        // 3. BEST-FIT LOGIC: Compare OEM and Internal
        val bestOemFile = if (callLogDetails != null) {
            FileUtils.findBestSystemRecording(logEndTime, systemDuration, finalNumber, finalCallerName)
        } else null

        when {
            bestOemFile != null && internalFileValid -> {
                val oemLength = FileUtils.getAudioDuration(bestOemFile)
                Log.d(TAG, "Comparing OEM ($oemLength s) and Internal ($internalFileDuration s)")
                if (systemDuration > (oemLength + 5) && internalFileDuration > oemLength) {
                    finalRecordingPath = internalFile.absolutePath
                    isMismatch = true
                    Log.d(TAG, "Choosing Internal recording (better duration)")
                } else {
                    finalRecordingPath = bestOemFile.absolutePath
                    internalFile.delete()
                    Log.d(TAG, "Choosing OEM recording")
                }
            }
            bestOemFile != null -> {
                finalRecordingPath = bestOemFile.absolutePath
                internalFile?.delete()
                Log.d(TAG, "Choosing OEM recording (Internal missing)")
            }
            internalFileValid -> {
                finalRecordingPath = internalFile.absolutePath
                // Only flag as mismatch if we picked up the call but are using internal recording
                isMismatch = wasActuallyPickedUp
                Log.d(TAG, "Choosing Internal recording (OEM missing)")
            }
            else -> {
                internalFile?.delete()
                Log.d(TAG, "No valid recording found for $callId")
            }
        }

        // 4. Save to DB
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val startStr = sdf.format(Date(logStartTime))
        val endStr = sdf.format(Date(logEndTime))

        // Use system-calculated answered time ONLY if the call was picked up
        val answeredAtStr = when {
            systemDuration > 0 -> sdf.format(Date(logEndTime - (systemDuration * 1000)))
            wasActuallyPickedUp && answeredTime > 0 -> sdf.format(Date(answeredTime))
            else -> null
        }

        // We save the log regardless of recordingEnabled, but we only attach a file if enabled and picked up
        val finalFilePath = if (registration.recordingEnabled && wasActuallyPickedUp) {
            finalRecordingPath
        } else {
            isMismatch = false // No mismatch if we are not sending a recording
            Log.d(TAG, "Not attaching recording (Enabled: ${registration.recordingEnabled}, PickedUp: $wasActuallyPickedUp)")
            if (finalRecordingPath != null && FileUtils.isAppRecording(applicationContext, finalRecordingPath)) {
                 File(finalRecordingPath).delete()
            }
            null
        }

        val uniqueId = FileUtils.generateUniqueId(registration.deviceUuid, startStr, endStr, finalNumber, finalType)
        val enrichedMetaData = mergeMetaDataWithCaptureDiagnostic(metaData, captureDiagnostics)

        captureDiagnostics?.let {
            Log.i(TAG, "Final capture verdict for $callId: ${it.toLogLine()}")
        }

        if (!callRepository.exists(uniqueId)) {
            val callLog = CallLogEntity(
                uniqueId = uniqueId,
                deviceUuid = registration.deviceUuid,
                clientNumber = finalNumber,
                callType = finalType,
                callDuration = if (systemDuration > 0) systemDuration else internalFileDuration,
                callStartedAt = startStr,
                callEndedAt = endStr,
                callAnsweredAt = answeredAtStr,
                callerName = finalCallerName,
                durationMismatch = isMismatch,
                recordingFilePath = finalFilePath,
                syncStatus = SyncStatus.PENDING,
                wasOnHold = wasOnHold,
                interruptedNumbers = interruptedNumbers.ifEmpty { null },
                latitude = location?.first?.toString(),
                longitude = location?.second?.toString(),
                batteryLevel = batteryLevel.toString(),
                apkVersion = apkVersion,
                spotSettingVersion = null,
                metaData = enrichedMetaData,
                retryCount = 0
            )
            callRepository.saveCallLog(callLog)
            Log.i("CALL-MAP", "✅ Call Processed: $callLog")

            // 1. Immediate attempt if online (Wait for it to keep service alive)
            try {
                callRepository.uploadPendingCallLogs(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Immediate upload attempt failed", e)
            }

            // 2. Reliable sync via WorkManager
            scheduleSync()
        } else {
            Log.w(TAG, "Duplicate call detected. Skipping save for $uniqueId")
        }
    }

    private fun scheduleSync() {
        Log.d(TAG, "Enqueuing immediate sync for call log...")
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val syncRequest = OneTimeWorkRequestBuilder<CallSyncWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
            
        // Use KEEP to avoid interrupting an ongoing upload from a previous call (e.g. in conference)
        // The existing worker will pick up all pending logs in its while-loop.
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            CallSyncWorker.IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            syncRequest
        )
    }

    private suspend fun getSystemCallLogDetails(number: String, startTimeMillis: Long, targetType: Int): SystemCallLogInfo? {
        val isUnknown = number == "Unknown" || number.isEmpty()
        val maxRetries = 10
        val retryDelayMs = 2000L

        repeat(maxRetries) { attempt ->
            try {
                val projection = arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.CACHED_NAME
                )
                val timeWindow = 15_000L // Increased window for better matching
                val selection = "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ?"
                val selectionArgs = arrayOf(
                    (startTimeMillis - timeWindow).toString(),
                    (startTimeMillis + timeWindow).toString()
                )

                val candidates = mutableListOf<SystemCallLogInfo>()

                contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    "${CallLog.Calls.DATE} DESC"
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val logNumber = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: ""
                        val logStart = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE))
                        val logDuration = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                        val type = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                        val name = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)) ?: getContactName(logNumber)

                        val isTypeMatch = if (targetType == CallLog.Calls.INCOMING_TYPE) {
                            type == CallLog.Calls.INCOMING_TYPE ||
                                    type == CallLog.Calls.MISSED_TYPE ||
                                    type == CallLog.Calls.REJECTED_TYPE ||
                                    type == 5 || // REJECTED_TYPE
                                    type == 6    // BLOCKED_TYPE
                        } else {
                            type == targetType
                        }

                        val isMatch = if (isUnknown) {
                            abs(logStart - startTimeMillis) <= 15_000 && isTypeMatch
                        } else {
                            val normLog = normalizeNumber(logNumber)
                            val normTarget = normalizeNumber(number)
                            (normLog == normTarget || (normLog.length >= 7 && normTarget.length >= 7 && (normLog.endsWith(normTarget) || normTarget.endsWith(normLog)))) && isTypeMatch
                        }

                        if (isMatch) {
                            candidates.add(SystemCallLogInfo(logNumber, name, logDuration, type, logStart))
                        }
                    }
                }

                if (candidates.isNotEmpty()) {
                    // Pick the candidate closest to our recorded start time
                    val best = candidates.minByOrNull { abs(it.timestamp - startTimeMillis) }
                    Log.d(TAG, "Found best call log match: ${best?.number} at ${best?.timestamp}")
                    return best
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching call log", e)
            }
            if (attempt < maxRetries - 1) delay(retryDelayMs)
        }
        return null
    }

    private data class SystemCallLogInfo(val number: String?, val name: String?, val duration: Long, val type: Int, val timestamp: Long)

    private fun buildCaptureDiagnostics(
        source: Int,
        recordedBytes: Long,
        totalReadBuffers: Long,
        zeroReadBuffers: Long,
        strongSignalBuffers: Long,
        maxPeakObserved: Int
    ): CaptureDiagnostics {
        val sourceName = when (source) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.MIC -> "MIC"
            else -> "UNKNOWN($source)"
        }

        val recordedSeconds = if (recordedBytes > 0) recordedBytes.toDouble() / (SAMPLE_RATE * 2.0) else 0.0
        val zeroRatio = if (totalReadBuffers > 0) zeroReadBuffers.toDouble() / totalReadBuffers.toDouble() else 1.0
        val strongRatio = if (totalReadBuffers > 0) strongSignalBuffers.toDouble() / totalReadBuffers.toDouble() else 0.0

        val (verdict, reason) = when {
            recordedSeconds < 1.0 || (zeroRatio > 0.97 && strongRatio < 0.02) -> "LIKELY_BLOCKED_OR_SILENT" to "almost all buffers silent"
            source == MediaRecorder.AudioSource.VOICE_COMMUNICATION && strongRatio >= 0.15 && zeroRatio < 0.90 -> "POSSIBLE_TWO_WAY" to "voice communication source with sustained non-silent signal"
            source == MediaRecorder.AudioSource.MIC || source == MediaRecorder.AudioSource.CAMCORDER -> "LIKELY_MIC_ONLY" to "fallback to mic-like source"
            else -> "UNCERTAIN" to "signal present but pattern is not conclusive"
        }

        return CaptureDiagnostics(
            source = source,
            sourceName = sourceName,
            recordedSeconds = recordedSeconds,
            zeroRatio = zeroRatio,
            strongSignalRatio = strongRatio,
            maxPeak = maxPeakObserved,
            verdict = verdict,
            reason = reason
        )
    }

    private fun mergeMetaDataWithCaptureDiagnostic(metaData: String?, diagnostics: CaptureDiagnostics?): String? {
        if (diagnostics == null) return metaData
        val captureFlag = diagnostics.toCompactFlag()
        return when {
            metaData.isNullOrBlank() -> captureFlag
            metaData.contains("captureVerdict=") -> metaData
            else -> "$metaData | $captureFlag"
        }
    }

    private fun normalizeNumber(number: String?): String {
        return number?.filter { it.isDigit() }?.takeLast(10) ?: ""
    }

    private fun getContactName(phoneNumber: String): String? {
        if (phoneNumber == "Unknown") return null
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
        } catch (e: Exception) { }
        return null
    }

    private fun getBatteryLevel(): Int {
        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun getApkVersion(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0" } catch (e: Exception) { "1.0" }

    private suspend fun getCurrentLocation(): Pair<Double, Double>? = suspendCancellableCoroutine { continuation ->
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) continuation.resume(Pair(location.latitude, location.longitude))
                else continuation.resume(null)
            }.addOnFailureListener { continuation.resume(null) }
        } catch (e: SecurityException) { continuation.resume(null) }
    }

    private fun updateWavHeader(file: File, totalAudioLen: Long) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = SAMPLE_RATE * 2
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte(); header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte(); header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0; header[22] = 1; header[23] = 0
        header[24] = (SAMPLE_RATE and 0xff).toByte(); header[25] = ((SAMPLE_RATE shr 8) and 0xff).toByte()
        header[26] = ((SAMPLE_RATE shr 16) and 0xff).toByte(); header[27] = ((SAMPLE_RATE shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte(); header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = 2; header[33] = 0; header[34] = 16; header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte(); header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte(); header[43] = ((totalAudioLen shr 24) and 0xff).toByte()
        val raf = RandomAccessFile(file, "rw")
        raf.seek(0); raf.write(header); raf.close()
    }

    private fun createNotification(content: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Call-Map").setContentText(content).setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Call Recording", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { super.onDestroy(); isRecording = false; serviceScope.cancel() }
}