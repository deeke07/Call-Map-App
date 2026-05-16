package com.callmap.agenttracker.util.file

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

object FileUtils {
    private const val TAG = "FileUtils"

    fun getPublicRecordingFolder(context: Context): File {
        val docFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "CallRecords")
        if (!docFolder.exists()) {
            docFolder.mkdirs()
        }
        return docFolder
    }

    fun isAppRecording(context: Context, filePath: String): Boolean {
        val appFolder = getPublicRecordingFolder(context).absolutePath
        return filePath.startsWith(appFolder)
    }

    fun getRecordingFile(context: Context, fileName: String): File {
        return File(getPublicRecordingFolder(context), fileName)
    }

    fun getSystemRecordingsDirectory(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        val brand = Build.BRAND.lowercase()
        val paths = mutableListOf<File>()

        // Common paths
        paths.add(File(root, "Call Recorder"))
        paths.add(File(root, "Recordings"))

        // Brand-specific paths
        when {
            brand.contains("poco") || brand.contains("xiaomi") || brand.contains("redmi") ->
                paths.addAll(listOf(
                    File(root, "Recordings/sound_recorder/call_rec"),
                    File(root, "MIUI/sound_recorder/call_rec"),
                    File(root, "recordings/call")
                ))
            brand.contains("samsung") ->
                paths.addAll(listOf(
                    File(root, "Recordings/Call"),
                    File(root, "Sounds/Call")
                ))
            brand.contains("vivo") ->
                paths.addAll(listOf(
                    File(root, "Recordings/Record/Call"),
                    File(root, "Record/Call")
                ))
            brand.contains("oppo") ->
                paths.addAll(listOf(
                    File(root, "Recordings/Call"),
                    File(root, "Sounds/Call")
                ))
            brand.contains("huawei") || brand.contains("honor") ->
                paths.addAll(listOf(
                    File(root, "Recordings/Call"),
                    File(root, "Sounds/Call")
                ))
            brand.contains("lava") ->
                paths.add(File(root, "Music"))
            brand.contains("oneplus") ->
                paths.addAll(listOf(
                    File(root, "Recordings/Call"),
                    File(root, "Sounds/Call")
                ))
        }

        return paths.filter { it.exists() && it.isDirectory }
    }

    fun generateUniqueId(deviceUuid: String, startedAt: String, endedAt: String, number: String, callType: Int): String {
        val normalized = number.filter { it.isDigit() }
        val suffix = if (normalized.length >= 10) normalized.takeLast(10) else normalized
        val raw = "$deviceUuid|$startedAt|$endedAt|$suffix|$callType"
        return MessageDigest.getInstance("MD5")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun getWavDuration(file: File): Long {
        return try {
            val bytes = file.length()
            if (bytes <= 44) return 0L
            (bytes - 44) / 88200 // PCM 16bit Mono 44.1kHz
        } catch (e: Exception) { 0L }
    }

    fun getAudioDuration(file: File): Long {
        if (file.extension.lowercase() == "wav") return getWavDuration(file)
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            retriever.release()
            durationMs / 1000
        } catch (e: Exception) { 0L }
    }

    private const val TIME_WINDOW_MS = 30_000L

    data class RecordingCandidate(
        val file: File,
        val score: Int,
        val duration: Long,
        val timeDiff: Long
    )

    suspend fun findBestSystemRecording(
        logEndTime: Long,
        systemDurationSec: Long,
        clientNumber: String,
        callerName: String?
    ): File? {
        val directories = getSystemRecordingsDirectory()
        val normalizedNumber = normalizeNumber(clientNumber)
        val normalizedName = callerName?.lowercase()?.trim()

        val candidates = mutableListOf<RecordingCandidate>()

        for (dir in directories) {
            dir.listFiles()?.filter { it.isFile && it.length() > 0 }?.forEach { file ->
                val fileTime = file.lastModified()
                val timeDiff = abs(fileTime - logEndTime)

                // Reject if outside time window
                if (timeDiff > TIME_WINDOW_MS) return@forEach

                val fileName = file.name.lowercase()
                val duration = getAudioDuration(file)

                // Reject invalid durations
                if (!isDurationValid(duration, systemDurationSec)) return@forEach

                // Reject unstable files
                if (!isFileStable(file)) return@forEach

                // Calculate score
                var score = 0

                // Number match (highest priority)
                if (normalizedNumber.isNotEmpty() && fileName.contains(normalizedNumber)) {
                    score += 50
                }

                // Name match
                if (!normalizedName.isNullOrBlank() && fileName.contains(normalizedName)) {
                    score += 30
                }

                // Duration accuracy
                val durationDiff = abs(duration - systemDurationSec)
                score += (20 - durationDiff.coerceAtMost(20)).toInt()

                // Time proximity
                val timeScore = (10 - (timeDiff / 3000)).toInt().coerceAtLeast(0)
                score += timeScore

                if (score > 0) {
                    candidates.add(RecordingCandidate(file, score, duration, timeDiff))
                }
            }
        }

        // Select highest scoring candidate
        val bestCandidate = candidates.maxByOrNull { it.score }

        return bestCandidate?.file
    }

    fun normalizeNumber(number: String?): String {
        return number?.filter { it.isDigit() }?.takeLast(10) ?: ""
    }

    fun isDurationValid(actual: Long, expected: Long): Boolean {
        if (actual <= 0 || expected < 0) return false

        val diff = abs(actual - expected)
        val tolerance = when {
            expected < 30 -> 3L
            expected < 120 -> 5L
            else -> 10L
        }

        return diff <= tolerance
    }

    suspend fun isFileStable(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false

        // Check file size stability over multiple reads
        val sizes = mutableListOf<Long>()
        repeat(3) {
            sizes.add(file.length())
            kotlinx.coroutines.delay(1000) // Non-blocking delay
        }

        // All sizes must be equal and > 0
        val firstSize = sizes.first()
        return firstSize > 0 && sizes.all { it == firstSize }
    }

    fun deleteOldFile(file: File) {
        val age = System.currentTimeMillis() - file.lastModified()
        if (age > 24 * 60 * 60 * 1000) { // 24 hours
            file.delete()
        }
    }
}
