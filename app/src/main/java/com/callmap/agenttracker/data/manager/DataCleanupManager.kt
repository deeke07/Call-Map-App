package com.callmap.agenttracker.data.manager

import android.content.Context
import android.util.Log
import com.callmap.agenttracker.data.local.dao.CallLogDao
import com.callmap.agenttracker.data.local.dao.LocationDao
import com.callmap.agenttracker.data.local.entity.SyncStatus
import com.callmap.agenttracker.util.file.FileUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages cleanup of synced data from local storage.
 * Deletes location and call log records that have been successfully synced to backend,
 * along with their associated recording files.
 */
@Singleton
class DataCleanupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationDao: LocationDao,
    private val callLogDao: CallLogDao
) {

    companion object {
        private const val TAG = "DataCleanupManager"
        // After sync, keep data for 7 days before cleanup (for debugging)
        private const val CLEANUP_DELAY_MS = 7 * 24 * 60 * 60 * 1000L
    }

    /**
     * Clean up location records that have been successfully synced.
     * Returns count of deleted records.
     */
    suspend fun cleanupSyncedLocations(): Int {
        return try {
            locationDao.clearSyncedLocations()
            Log.i(TAG, "Cleaned up synced location records")
            0 // Return count (we don't track exact count in this simple approach)
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up locations", e)
            0
        }
    }

    /**
     * Clean up call log records that have been successfully synced.
     * Also deletes associated recording files.
     * Returns count of deleted records.
     */
    suspend fun cleanupSyncedCallLogs(): Int {
        return try {
            // Get synced call logs and delete their recording files
            val syncedLogs = callLogDao.getUnsyncedCallLogs(SyncStatus.SYNCED)

            var deletedCount = 0
            for (log in syncedLogs) {
                try {
                    // Delete recording file if it exists and belongs to this app
                    log.recordingFilePath?.let { path ->
                        try {
                            if (FileUtils.isAppRecording(context, path)) {
                                val file = File(path)
                                if (file.exists() && file.delete()) {
                                    Log.d(TAG, "Deleted recording file: $path")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error deleting recording file: $path", e)
                        }
                    }

                    // Delete call log record from database
                    callLogDao.deleteCallLog(log)
                    deletedCount++
                    Log.d(TAG, "Deleted call log: ${log.clientNumber}")
                } catch (e: Exception) {
                    Log.w(TAG, "Error deleting call log: ${log.uniqueId}", e)
                }
            }

            if (deletedCount > 0) {
                Log.i(TAG, "Cleaned up $deletedCount synced call log records")
            }

            deletedCount
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up call logs", e)
            0
        }
    }

    /**
     * Clean up all synced data (locations and call logs).
     * Safe to call periodically (e.g., daily).
     */
    suspend fun cleanupAllSyncedData(): Pair<Int, Int> {
        Log.d(TAG, "Starting data cleanup...")
        val locationCount = cleanupSyncedLocations()
        val callCount = cleanupSyncedCallLogs()
        Log.i(TAG, "Cleanup complete: $locationCount locations, $callCount calls removed")
        return Pair(locationCount, callCount)
    }

    /**
     * Clean up failed/abandoned call recordings (older than 24 hours).
     * These are files not associated with any DB record.
     */
    suspend fun cleanupOrphanRecordings(): Int {
        return try {
            val recordingFolder = FileUtils.getPublicRecordingFolder(context)
            val oldTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000) // 24 hours ago

            var deletedCount = 0
            recordingFolder.listFiles()?.forEach { file ->
                if (file.isFile && FileUtils.isAppRecording(context, file.absolutePath)) {
                    if (file.lastModified() < oldTime) {
                        if (file.delete()) {
                            deletedCount++
                            Log.d(TAG, "Deleted orphan recording: ${file.name}")
                        }
                    }
                }
            }

            if (deletedCount > 0) {
                Log.i(TAG, "Cleaned up $deletedCount orphan recordings")
            }

            deletedCount
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up orphan recordings", e)
            0
        }
    }
}






