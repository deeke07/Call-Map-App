package com.callmap.agenttracker.data.manager

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import com.callmap.agenttracker.data.remote.api.CallApi
import com.callmap.agenttracker.data.remote.dto.DeviceSimBulkRequest
import com.callmap.agenttracker.data.remote.dto.SimRequestDto
import com.callmap.agenttracker.domain.manager.DeviceSimManager
import com.callmap.agenttracker.domain.manager.SessionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceSimManagerImpl @Inject constructor(
    private val context: Context,
    private val callApi: CallApi,
    private val sessionManager: SessionManager
) : DeviceSimManager {

    private val syncMutex = Mutex()

    private val subscriptionManager by lazy {
        context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
    }

    @SuppressLint("MissingPermission")
    override suspend fun getActiveSims(): List<SimRequestDto> {
        return try {
            val activeSubscriptionInfoList = subscriptionManager.activeSubscriptionInfoList
            activeSubscriptionInfoList?.map { info ->
                SimRequestDto(
                    simSlot = info.simSlotIndex + 1, // API usually expects 1-based index
                    carrierName = info.displayName.toString(),
                    phoneNumber = getPhoneNumber(info),
                    iccid = info.iccId,
                    mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.mccString else info.mcc.toString(),
                    mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.mncString else info.mnc.toString(),
                    countryIso = info.countryIso,
                    isActive = true
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e("DeviceSimManager", "Error fetching active SIMs", e)
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    private fun getPhoneNumber(info: SubscriptionInfo): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                subscriptionManager.getPhoneNumber(info.subscriptionId)
            } else {
                info.number
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun syncSimsWithBackend(): Result<Unit> = syncMutex.withLock {
        return try {
            val registration = sessionManager.getRegistration().first() ?: return Result.failure(Exception("Not registered"))
            val sims = getActiveSims()
            
            val request = DeviceSimBulkRequest(
                deviceUuid = registration.deviceUuid,
                sims = sims
            )

            val response = callApi.registerDeviceSims(request)
            if (response.isSuccessful && response.body()?.success == true) {
                val data = response.body()?.data
                
                // Cache mappings during sync
                val activeInfo = try { subscriptionManager.activeSubscriptionInfoList } catch (e: Exception) { null }
                
                data?.forEach { simResponse ->
                    sessionManager.saveSimUuid(simResponse.simSlot, simResponse.uuid)
                    
                    // Save mapping: Link hardware details to slot
                    activeInfo?.find { it.simSlotIndex + 1 == simResponse.simSlot }?.let { info ->
                        sessionManager.saveSimSubIdMapping(info.subscriptionId.toString(), simResponse.simSlot)
                        if (info.iccId != null) {
                            sessionManager.saveSimSubIdMapping(info.iccId, simResponse.simSlot)
                        }
                    }
                }
                Log.d("DeviceSimManager", "SIMs synced and mappings cached: $data")
                Result.success(Unit)
            } else {
                Result.failure(Exception("API call failed: ${response.message()}"))
            }
        } catch (e: Exception) {
            Log.e("DeviceSimManager", "Error syncing SIMs", e)
            Result.failure(e)
        }
    }

    override suspend fun getSimUuidForSlot(slotIndex: Int): String? {
        return sessionManager.getSimUuid(slotIndex)
    }

    @SuppressLint("MissingPermission")
    override suspend fun getCarrierNameForSlot(slotIndex: Int): String? {
        return try {
            val activeSubscriptionInfoList = subscriptionManager.activeSubscriptionInfoList
            activeSubscriptionInfoList?.find { it.simSlotIndex + 1 == slotIndex }?.displayName?.toString()
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun getSimSlotFromSubscriptionId(subId: String?): Int? {
        val activeInfo = try { subscriptionManager.activeSubscriptionInfoList } catch (e: Exception) { null }
        
        Log.d("DeviceSimManager", "Resolving SIM for subId: $subId. Active SIMs: ${activeInfo?.size ?: 0}")

        // Strategy 1: If only one SIM is active, it's highly likely the one used for the call
        if (activeInfo?.size == 1) {
            val slot = activeInfo[0].simSlotIndex + 1
            Log.d("DeviceSimManager", "Single SIM detected. Defaulting to slot: $slot")
            return slot
        }

        if (subId == null || subId.isEmpty()) {
            Log.d("DeviceSimManager", "subId is null/empty and multiple SIMs present. Cannot resolve.")
            return null
        }

        // Strategy 0: Check cached mappings (Best for rooted/custom ROMs)
        sessionManager.getSlotFromSubIdMapping(subId)?.let { cachedSlot ->
            Log.d("DeviceSimManager", "Found cached mapping for subId $subId -> Slot $cachedSlot")
            return cachedSlot
        }
        
        return try {
            // Strategy 2: Direct integer match (Standard Android)
            val id = subId.toIntOrNull()
            if (id != null) {
                val info = subscriptionManager.getActiveSubscriptionInfo(id)
                if (info != null) {
                    val slot = info.simSlotIndex + 1
                    Log.d("DeviceSimManager", "Matched subId as integer. Slot: $slot")
                    return slot
                }
            }

            // Strategy 3: Partial string match or ICCID match (Common on rooted/custom ROMs)
            val matchedInfo = activeInfo?.find { info ->
                subId == info.subscriptionId.toString() ||
                subId == info.iccId ||
                (info.iccId != null && subId.contains(info.iccId)) ||
                subId.contains(info.subscriptionId.toString())
            }
            
            if (matchedInfo != null) {
                val slot = matchedInfo.simSlotIndex + 1
                Log.d("DeviceSimManager", "Matched subId via string/ICCID. Slot: $slot")
                return slot
            }

            // Strategy 4: Extract digits from a handle (e.g. "SIM1", "account_1")
            val extractedId = subId.filter { it.isDigit() }.toIntOrNull()
            if (extractedId != null) {
                val info = activeInfo?.find { it.subscriptionId == extractedId || it.simSlotIndex + 1 == extractedId }
                if (info != null) {
                    val slot = info.simSlotIndex + 1
                    Log.d("DeviceSimManager", "Matched extracted ID $extractedId. Slot: $slot")
                    return slot
                }
            }

            Log.w("DeviceSimManager", "Failed to resolve SIM slot for subId: $subId")
            null
        } catch (e: Exception) {
            Log.e("DeviceSimManager", "Error resolving subId: $subId", e)
            null
        }
    }
}
