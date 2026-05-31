package com.callmap.agenttracker.data.repository

import android.util.Log
import com.callmap.agenttracker.data.local.LocationSpilloverStore
import com.callmap.agenttracker.data.local.dao.LocationDao
import com.callmap.agenttracker.data.local.entity.LocationEntity
import com.callmap.agenttracker.data.local.entity.SyncStatus
import com.callmap.agenttracker.data.manager.DataCleanupManager
import com.callmap.agenttracker.data.remote.api.LocationApi
import com.callmap.agenttracker.data.remote.dto.BulkLocationRequest
import com.callmap.agenttracker.data.remote.dto.LocationItem
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.domain.repository.LocationRepository
import com.callmap.agenttracker.util.NetworkObserver
import com.callmap.agenttracker.util.Resource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

class LocationRepositoryImpl @Inject constructor(
    private val api: LocationApi,
    private val dao: LocationDao,
    private val sessionManager: SessionManager,
    private val networkObserver: NetworkObserver,
    private val cleanupManager: DataCleanupManager,
    private val spilloverStore: LocationSpilloverStore
) : LocationRepository {

    private val syncMutex = Mutex()

    companion object {
        private const val TAG = "LocationRepository"
        private const val BATCH_SIZE = 100
    }

    override suspend fun saveLocation(location: LocationEntity) {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                dao.insertLocation(location)
              //  Log.d(TAG, "Location saved locally: ${location.latitude}, ${location.longitude}")
                return
            } catch (e: Exception) {
                lastError = e
                Log.e(TAG, "Room insert attempt ${attempt + 1} failed: ${e.message}")
                if (attempt < 2) delay(200L * (attempt + 1))
            }
        }
        Log.e(TAG, "Room insert failed — writing to spillover file: ${lastError?.message}")
        spilloverStore.append(location, source = "room_insert_failed")
    }

    override suspend fun importSpilloverLocations(): Int {
        val lines = spilloverStore.readAll()
        if (lines.isEmpty()) return 0

        val remaining = mutableListOf<LocationSpilloverStore.SpillLine>()
        var imported = 0
        for (line in lines) {
            try {
                dao.insertLocation(line.toEntity())
                imported++
            } catch (e: Exception) {
                Log.w(TAG, "Spillover import failed for (${line.latitude},${line.longitude}): ${e.message}")
                remaining.add(line)
            }
        }
        spilloverStore.rewrite(remaining)
        if (imported > 0) Log.i(TAG, "Imported $imported locations from spillover (${remaining.size} remaining)")
        return imported
    }

    override suspend fun uploadSingleLocation(location: LocationEntity): Resource<Unit> {
        return syncPendingLocations().let { 
            if (it is Resource.Success) Resource.Success(Unit) 
            else Resource.Error(it.message ?: "Sync failed")
        }
    }

    override suspend fun syncPendingLocations(): Resource<Int> {
        val unsyncedCount = dao.getUnsyncedCount()
        Log.i(TAG, "Sync Session Triggered (Pending: $unsyncedCount)")
        
        var isReady = networkObserver.isConnected()
        if (!isReady) {
            Log.d(TAG, "Network not ready yet, waiting 3s...")
            delay(3000)
            isReady = networkObserver.isConnected()
        }

        if (!isReady) {
            Log.w(TAG, "Sync aborted: Network reports DISCONNECTED.")
            return Resource.Error("No internet connection")
        }

        return try {
            syncMutex.withLock {
                importSpilloverLocations()
                var totalSynced = 0
                var consecutiveFailures = 0
                
                while (true) {
                    val pending = dao.getUnsyncedLocations(limit = BATCH_SIZE)
                    if (pending.isEmpty()) {
                        Log.d(TAG, "Sync loop finished: All pending locations processed.")
                        break
                    }

                    Log.d(TAG, "Processing batch of ${pending.size} (Synced: $totalSynced)")
                    val result = syncBatchInternal(pending)
                    
                    if (result is Resource.Success) {
                        totalSynced += (result.data ?: 0)
                        consecutiveFailures = 0 // Reset on success
                        Log.d(TAG, "Batch synced: ${result.data} items.")
                    } else {
                        // Handle DNS/Transient errors with a short retry
                        if (consecutiveFailures < 2 && (result.message?.contains("Unable to resolve host") == true)) {
                            consecutiveFailures++
                            Log.w(TAG, "DNS/Host not ready. Retrying batch in 5s... (Attempt $consecutiveFailures)")
                            delay(5000)
                            continue 
                        }
                        
                        runCatching {
                            dao.updateSyncStatus(pending.map { it.id }, SyncStatus.FAILED)
                        }
                        Log.e(TAG, "Batch failed: ${result.message}. Session halted.")
                        return if (totalSynced > 0) Resource.Success(totalSynced) else result
                    }
                }
                
                if (totalSynced > 0) {
                    Log.i(TAG, "Sync Complete: Total $totalSynced locations.")
                    // Cleanup synced records after successful sync
                    cleanupManager.cleanupSyncedLocations()
                }
                Resource.Success(totalSynced)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync Session FATAL: ${e.message}")
            Resource.Error(e.message ?: "Sync failed")
        } finally {
            Log.d(TAG, "Sync Session Ended")
        }
    }

    private suspend fun syncBatchInternal(batch: List<LocationEntity>): Resource<Int> {
        val registration = sessionManager.getRegistration().first() ?: return Resource.Error("Not registered")

        val request = BulkLocationRequest(
            deviceUuid = registration.deviceUuid,
            locations = batch.map {
                LocationItem(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    batteryLevel = it.batteryLevel,
                    recordedAt = it.recordedAt
                )
            }
        )

        return try {
            val response = api.submitBulkLocations(request)
            if (response.isSuccessful && response.body()?.success == true) {
                val syncedIds = batch.map { it.id }
                dao.updateSyncStatus(syncedIds, SyncStatus.SYNCED)
                dao.deleteLocations(batch)
                
                Log.d(TAG, "Batch confirmed by server. ${batch.size} points removed from local storage.")
                Resource.Success(batch.size)
            } else {
                val err = "API Error ${response.code()}: ${response.message()}"
                Log.e(TAG, err)
                Resource.Error(err)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network transport error: ${e.message}")
            Resource.Error(e.message ?: "Network error")
        }
    }
}
