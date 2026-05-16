package com.callmap.agenttracker.data.manager

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.callmap.agenttracker.domain.manager.ServiceManager
import com.callmap.agenttracker.service.LocationService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServiceManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ServiceManager {

    companion object {
        private const val CHANNEL_ID = "location_tracking_v4"
    }

    override fun startServices() {
        Log.d("ServiceManager", "Starting services")
    }

    override fun stopServices() {
        Log.d("ServiceManager", "Stopping all services")
        context.stopService(Intent(context, LocationService::class.java))
    }

    override fun handleServiceLifecycle(trackingEnabled: Boolean) {
        val isRunning = isServiceRunning(LocationService::class.java)
        Log.d("ServiceManager", "handleServiceLifecycle: trackingEnabled=$trackingEnabled, isRunning=$isRunning")
        
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
            if (!isRunning) {
                Log.d("ServiceManager", "LocationService not running. Skipping stop.")
                return
            }
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

    override fun createNotification(content: String): Notification {
        createNotificationChannel()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Call-Map")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Location Tracking", NotificationManager.IMPORTANCE_LOW
            ).apply {
                enableVibration(false)
                setSound(null, null)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
