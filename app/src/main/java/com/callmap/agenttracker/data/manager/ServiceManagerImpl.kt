package com.callmap.agenttracker.data.manager

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.callmap.agenttracker.domain.manager.ServiceManager
import com.callmap.agenttracker.service.LocationService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServiceManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ServiceManager {

    override fun startServices() {
        Log.d("ServiceManager", "Starting services")
    }

    override fun stopServices() {
        Log.d("ServiceManager", "Stopping all services")
        context.stopService(Intent(context, LocationService::class.java))
    }

    override fun handleServiceLifecycle(trackingEnabled: Boolean) {
        Log.d("ServiceManager", "handleServiceLifecycle: trackingEnabled=$trackingEnabled")
        
        if (trackingEnabled) {
            val intent = Intent(context, LocationService::class.java).apply {
                action = LocationService.ACTION_START
            }
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("ServiceManager", "Error starting LocationService", e)
            }
        } else {
            val intent = Intent(context, LocationService::class.java).apply {
                action = LocationService.ACTION_STOP
            }
            context.stopService(intent)
        }
    }

    override fun <T : Service> isServiceRunning(serviceClass: Class<T>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
