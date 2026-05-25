package com.callmap.agenttracker.util.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log

/**
 * Manages call recording with intelligent fallback mechanism for devices with
 * audio capture restrictions (especially Samsung devices on Android 12+).
 *
 * Strategy:
 * 1. Try VOICE_CALL (captures both sides on unrestricted devices)
 * 2. Fallback to MIC + Speaker activation (for restricted devices)
 * 3. Handle audio session setup, cleanup, and error recovery
 */
class CallRecordingManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioRecord: AudioRecord? = null
    private var originalSpeakerState = false
    private var originalAudioMode = AudioManager.MODE_NORMAL
    private var originalVolume = -1
    private var isUsingMicFallback = false
    private var noiseSuppressor: android.media.audiofx.NoiseSuppressor? = null

    companion object {
        private const val TAG = "CallRecordingManager"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Device detection
        private fun isSamsungDevice(): Boolean {
            return Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        }

        private fun isRestrictedDevice(): Boolean {
            return isSamsungDevice() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S // Android 12+
        }
    }

    /**
     * Initialize recording with intelligent audio source selection.
     * Returns true if recording was successfully initialized.
     */
    fun initializeRecording(): Boolean {
        return try {
            // Try VOICE_CALL first unless device is known to be restricted
            if (!isRestrictedDevice()) {
                if (tryInitializeWithAudioSource(MediaRecorder.AudioSource.VOICE_CALL)) {
                    isUsingMicFallback = false
                    Log.d(TAG, "✓ Recording initialized with VOICE_CALL")
                    return true
                }
            }

            // Fallback to MIC + Speaker strategy
            if (tryInitializeWithAudioSource(MediaRecorder.AudioSource.MIC)) {
                isUsingMicFallback = true
                enableSpeakerForBothSideCapture()
                Log.d(TAG, "✓ Recording initialized with MIC + Speaker fallback")
                return true
            }

            // Final fallback: Try VOICE_RECOGNITION
            if (tryInitializeWithAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)) {
                isUsingMicFallback = true
                Log.d(TAG, "✓ Recording initialized with VOICE_RECOGNITION fallback")
                return true
            }

            Log.e(TAG, "✗ Failed to initialize recording with any audio source")
            cleanup()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error during recording initialization", e)
            cleanup()
            false
        }
    }

    /**
     * Try to initialize AudioRecord with specified audio source.
     */
    private fun tryInitializeWithAudioSource(audioSource: Int): Boolean {
        return try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (bufferSize <= 0) {
                Log.w(TAG, "Invalid buffer size for source $audioSource")
                return false
            }

            val record = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2 // Use larger buffer for stability
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                Log.w(TAG, "AudioRecord not initialized for source $audioSource")
                return false
            }

            // Try to start recording
            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                    record.release()
                    Log.w(TAG, "Failed to start recording for source $audioSource")
                    return false
                }
            } catch (e: Exception) {
                record.release()
                Log.w(TAG, "Exception starting recording for source $audioSource: ${e.message}")
                return false
            }

            // Success - store the record and enable noise suppression if available
            this.audioRecord = record
            enableNoiseSuppression(record.audioSessionId)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Error initializing with source $audioSource: ${e.message}")
            false
        }
    }

    /**
     * Enable noise suppressor if available on device.
     */
    private fun enableNoiseSuppression(audioSessionId: Int) {
        try {
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                noiseSuppressor = android.media.audiofx.NoiseSuppressor.create(audioSessionId)
                noiseSuppressor?.enabled = true
                Log.d(TAG, "Noise suppression enabled")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enable noise suppression: ${e.message}")
        }
    }

    /**
     * Enable speakerphone to ensure both sides of conversation are captured via microphone.
     * This is used as fallback when VOICE_CALL is not available.
     */
    private fun enableSpeakerForBothSideCapture() {
        try {
            // Save original state
            originalSpeakerState = audioManager.isSpeakerphoneOn
            originalAudioMode = audioManager.mode
            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)

            // Set to communication mode for optimal audio routing
            @Suppress("DEPRECATION")
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // Enable speaker to route audio through speaker so microphone can pick it up
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true

            // Reduce volume to prevent feedback and clipping
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val targetVol = (maxVol * 0.85f).toInt().coerceAtLeast(1) // Cap at 85%
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, targetVol, 0)

            Log.d(TAG, "Speaker enabled for MIC recording. Audio mode: ${audioManager.mode}")
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling speaker", e)
        }
    }

    /**
     * Read audio data from microphone.
     * Returns the number of bytes read, or < 0 on error.
     */
    fun readAudio(audioBuffer: ShortArray): Int {
        return try {
            val record = audioRecord ?: return -1
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return -1
            }
            record.read(audioBuffer, 0, audioBuffer.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading audio", e)
            -1
        }
    }

    /**
     * Stop recording and restore original audio settings.
     */
    fun stopRecording() {
        try {
            audioRecord?.apply {
                try {
                    if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        stop()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping recording", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in stopRecording", e)
        }
    }

    /**
     * Clean up all resources and restore original audio settings.
     */
    fun cleanup() {
        try {
            // Stop recording if active
            stopRecording()

            // Release audio resources
            noiseSuppressor?.apply {
                try {
                    release()
                } catch (e: Exception) {}
            }
            noiseSuppressor = null

            audioRecord?.apply {
                try {
                    release()
                } catch (e: Exception) {}
            }
            audioRecord = null

            // Restore audio settings only if we modified them
            if (isUsingMicFallback) {
                restoreAudioSettings()
            }

            isUsingMicFallback = false
            Log.d(TAG, "Recording cleanup complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    /**
     * Restore original audio mode and speaker state.
     */
    private fun restoreAudioSettings() {
        try {
            // Restore speaker state
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = originalSpeakerState

            // Restore audio mode
            @Suppress("DEPRECATION")
            audioManager.mode = originalAudioMode

            // Restore volume
            if (originalVolume != -1) {
                audioManager.setStreamVolume(
                    AudioManager.STREAM_VOICE_CALL,
                    originalVolume,
                    0
                )
            }

            Log.d(TAG, "Audio settings restored to original state")
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring audio settings", e)
        }
    }

    /**
     * Check if recording is currently active.
     */
    fun isRecordingActive(): Boolean {
        return audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
    }

    /**
     * Get info about current recording setup.
     */
    fun getRecordingInfo(): RecordingInfo {
        return RecordingInfo(
            isInitialized = audioRecord != null,
            isActive = isRecordingActive(),
            isUsingMicFallback = isUsingMicFallback,
            isSamsungDevice = isSamsungDevice(),
            isRestrictedDevice = isRestrictedDevice(),
            audioSessionId = audioRecord?.audioSessionId ?: -1
        )
    }

    data class RecordingInfo(
        val isInitialized: Boolean,
        val isActive: Boolean,
        val isUsingMicFallback: Boolean,
        val isSamsungDevice: Boolean,
        val isRestrictedDevice: Boolean,
        val audioSessionId: Int
    )
}


