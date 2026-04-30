package com.callmap.agenttracker.data.repository

import android.util.Log
import com.callmap.agenttracker.data.local.dao.LocationDao
import com.callmap.agenttracker.data.local.entity.LocationEntity
import com.callmap.agenttracker.data.local.entity.SyncStatus
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
    private val networkObserver: NetworkObserver
) : LocationRepository {

    private val syncMutex = Mutex()

    companion object {
        private const val TAG = "LocationRepository"
        private const val BATCH_SIZE = 100
    }

    override suspend fun saveLocation(location: LocationEntity) {
        dao.insertLocation(location)
        Log.d(TAG, "Location saved locally: ${location.latitude}, ${location.longitude}")
    }

    override suspend fun uploadSingleLocation(location: LocationEntity): Resource<Unit> {
        return syncPendingLocations().let { 
            if (it is Resource.Success) Resource.Success(Unit) 
            else Resource.Error(it.message ?: "Sync failed")
        }
    }

    override suspend fun syncPendingLocations(): Resource<Int> {
        Log.i(TAG, ">>> Sync Session TRIGGERED <<<")
        
        // Warm-up: Sometimes the network interface is up but not yet routing data
        var isReady = networkObserver.isConnected()
        if (!isReady) {
            Log.d(TAG, "Network not ready yet, waiting 2.5s...")
            delay(2500)
            isReady = networkObserver.isConnected()
        }

        if (!isReady) {
            Log.w(TAG, "Sync aborted: Network reports DISCONNECTED after warm-up.")
            return Resource.Error("No internet connection")
        }

        return try {
            syncMutex.withLock {
                var totalSynced = 0
                
                while (true) {
                    val pending = dao.getUnsyncedLocations(limit = BATCH_SIZE)
                    if (pending.isEmpty()) {
                        Log.d(TAG, "Sync loop finished: No more pending locations.")
                        break
                    }

                    Log.i(TAG, "Processing batch of ${pending.size} locations...")
                    val result = syncBatchInternal(pending)
                    if (result is Resource.Success) {
                        totalSynced += (result.data ?: 0)
                    } else {
                        Log.e(TAG, "Batch failed: ${result.message}. Session halted.")
                        return if (totalSynced > 0) Resource.Success(totalSynced) else result
                    }
                }
                
                if (totalSynced > 0) {
                    Log.i(TAG, "✓ SUCCESS: Synced total of $totalSynced locations.")
                }
                Resource.Success(totalSynced)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync Session FATAL: ${e.message}")
            Resource.Error(e.message ?: "Sync failed")
        } finally {
            Log.i(TAG, "<<< Sync Session ENDED >>>")
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
