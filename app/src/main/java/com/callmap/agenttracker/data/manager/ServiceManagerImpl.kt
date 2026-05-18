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
        private const val CHANNEL_LOCATION = "location_tracking_v4"
        private const val CHANNEL_FCM = "fcm_default_v1"
        private const val CHANNEL_RECORDING = "call_recording_channel"
    }

    override fun startServices() {
        Log.d("ServiceManager", "Starting services")
        setupNotificationChannels()
    }

    override fun stopServices() {
        Log.d("ServiceManager", "Stopping all services")
        context.stopService(Intent(context, LocationService::class.java))
    }

    override fun setupNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. Location Channel (Low importance, silent)
            val locationChannel = NotificationChannel(
                CHANNEL_LOCATION, 
                "Agent Tracking", 
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Continuous background location tracking"
                enableVibration(false)
                setSound(null, null)
                setShowBadge(false)
            }

            // 2. FCM Channel (High importance, heads-up)
            val fcmChannel = NotificationChannel(
                CHANNEL_FCM, 
                "General Notifications", 
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for settings updates and call requests"
                enableVibration(true)
                setShowBadge(true)
            }

            // 3. Recording Channel (Low importance)
            val recordingChannel = NotificationChannel(
                CHANNEL_RECORDING, 
                "Call Recording", 
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground notification for active call recording"
                enableVibration(false)
                setSound(null, null)
            }

            notificationManager.createNotificationChannels(listOf(locationChannel, fcmChannel, recordingChannel))
            Log.d("ServiceManager", "Notification channels initialized: $CHANNEL_LOCATION, $CHANNEL_FCM, $CHANNEL_RECORDING")
        }
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
        return NotificationCompat.Builder(context, CHANNEL_LOCATION)
            .setContentTitle("Call-Map")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
