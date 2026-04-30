package com.callmap.agenttracker.service.controller

import android.content.Context
import android.content.Intent
import android.os.Build
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.service.LocationService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: SessionManager
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    fun syncServicesWithConfig() {
        scope.launch {
            val config = sessionManager.getRegistration().first() ?: return@launch
            
            if (config.trackingEnabled) {
                startLocationService()
            } else {
                stopLocationService()
            }
            
            // Call recording is typically handled by CallReceiver based on config,
            // but we can ensure it's "enabled" in terms of any background state if needed.
        }
    }

    private fun startLocationService() {
        val intent = Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopLocationService() {
        val intent = Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_STOP
        }
        context.startService(intent)
    }
}
