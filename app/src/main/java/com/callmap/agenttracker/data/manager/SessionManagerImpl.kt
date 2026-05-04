package com.callmap.agenttracker.data.manager

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.domain.model.RegistrationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "session_prefs")

@Singleton
class SessionManagerImpl @Inject constructor(
    private val context: Context
) : SessionManager {

    companion object {
        private val DEVICE_UUID = stringPreferencesKey("device_uuid")
        private val DEVICE_NAME = stringPreferencesKey("device_name")
        private val RECORDING_ENABLED = booleanPreferencesKey("recording_enabled")
        private val TRACKING_ENABLED = booleanPreferencesKey("tracking_enabled")
        private val LOCATION_FREQUENCY = longPreferencesKey("location_frequency")
        private val AGENT_EMAIL = stringPreferencesKey("agent_email")
        private val AGENT_NAME = stringPreferencesKey("agent_name")
        private val AGENT_PROFILE = stringPreferencesKey("agent_profile")
        private val LOCATION_ON_CALL = booleanPreferencesKey("location_on_call")
        private val LOCATION_HIGH_ACCURACY = booleanPreferencesKey("location_high_accuracy")
        private val REMOTE_LOCK = booleanPreferencesKey("remote_lock")
        private val LAST_SIM_ID = stringPreferencesKey("last_sim_id")
        private val TRACKING_DAYS = stringSetPreferencesKey("tracking_days")
        private val TRACKING_START_TIME = stringPreferencesKey("tracking_start_time")
        private val TRACKING_END_TIME = stringPreferencesKey("tracking_end_time")
        
        // Dynamic State Keys Prefix
        private const val STATE_PREFIX = "state_"
    }

    override suspend fun saveRegistration(registration: RegistrationResult) {
        context.dataStore.edit { prefs ->
            prefs[DEVICE_UUID] = registration.deviceUuid
            prefs[DEVICE_NAME] = registration.deviceName
            prefs[RECORDING_ENABLED] = registration.recordingEnabled
            prefs[TRACKING_ENABLED] = registration.trackingEnabled
            prefs[LOCATION_FREQUENCY] = registration.locationFrequency
            prefs[AGENT_EMAIL] = registration.agentEmail ?: ""
            prefs[AGENT_NAME] = registration.agentName ?: ""
            prefs[AGENT_PROFILE] = registration.agentProfile ?: ""
            prefs[LOCATION_ON_CALL] = registration.locationOnCall
            prefs[LOCATION_HIGH_ACCURACY] = registration.locationHighAccuracy
            prefs[REMOTE_LOCK] = registration.remoteLock
            prefs[LAST_SIM_ID] = registration.lastSimId ?: ""
            prefs[TRACKING_DAYS] = registration.trackingDays.toSet()
            prefs[TRACKING_START_TIME] = registration.trackingStartTime ?: ""
            prefs[TRACKING_END_TIME] = registration.trackingEndTime ?: ""
        }
    }

    override fun getRegistration(): Flow<RegistrationResult?> {
        return context.dataStore.data.map { prefs ->
            val uuid = prefs[DEVICE_UUID] ?: return@map null
            RegistrationResult(
                deviceUuid = uuid,
                deviceName = prefs[DEVICE_NAME] ?: "",
                recordingEnabled = prefs[RECORDING_ENABLED] ?: true,
                trackingEnabled = prefs[TRACKING_ENABLED] ?: true,
                locationFrequency = prefs[LOCATION_FREQUENCY] ?: 300L,
                agentEmail = prefs[AGENT_EMAIL] ?: "",
                agentName = prefs[AGENT_NAME] ?: "",
                agentProfile = prefs[AGENT_PROFILE] ?: "",
                locationOnCall = prefs[LOCATION_ON_CALL] ?: true,
                locationHighAccuracy = prefs[LOCATION_HIGH_ACCURACY] ?: true,
                remoteLock = prefs[REMOTE_LOCK] ?: false,
                lastSimId = prefs[LAST_SIM_ID],
                trackingDays = prefs[TRACKING_DAYS]?.toList() ?: emptyList(),
                trackingStartTime = prefs[TRACKING_START_TIME]?.ifEmpty { null },
                trackingEndTime = prefs[TRACKING_END_TIME]?.ifEmpty { null }
            )
        }
    }

    override suspend fun clearSession() {
        context.dataStore.edit { it.clear() }
    }

    override fun getDeviceStates(): Flow<Map<String, String>> {
        return context.dataStore.data.map { prefs ->
            prefs.asMap()
                .filterKeys { it.name.startsWith(STATE_PREFIX) }
                .mapKeys { it.key.name.removePrefix(STATE_PREFIX) }
                .mapValues { it.value.toString() }
        }
    }

    override suspend fun updateDeviceState(key: String, value: String) {
        val prefKey = stringPreferencesKey(STATE_PREFIX + key)
        context.dataStore.edit { prefs ->
            prefs[prefKey] = value
        }
    }
}
