package com.callmap.agenttracker.service.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.callmap.agenttracker.MainActivity
import com.callmap.agenttracker.R
import com.callmap.agenttracker.domain.usecase.FetchConfigUseCase
import com.callmap.agenttracker.domain.manager.ServiceManager
import com.callmap.agenttracker.service.CallReceiver
import com.callmap.agenttracker.service.controller.ServiceController
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MyFirebaseMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var fetchConfigUseCase: FetchConfigUseCase

    @Inject
    lateinit var serviceController: ServiceController

    @Inject
    lateinit var serviceManager: ServiceManager

    companion object {
        private const val CHANNEL_ID = "fcm_default_v1"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.i("FCM", "Message received! Data payload: ${message.data}")

        // Heartbeat/Watchdog: App is alive via FCM
        serviceManager.runWatchdogCheck()

        val type = message.data["type"]
        val title = message.data["title"] ?: "Settings Updated"
        val body = message.data["body"] ?: "New configuration has been applied."

        when (type) {
            "dial_request" -> handleDialRequest(message.data)
            "device_settings_changed" -> handleSettingsChange(title, body)
            else -> showNotification(title, body)
        }
    }

    private fun handleDialRequest(data: Map<String, String>) {
        val mobileNumber = data["mobile_number"]
        val metaData = data["meta_data"]
        val title = data["title"] ?: "Incoming Dial Request"
        val body = data["body"] ?: "You have a new call request"

        if (mobileNumber != null) {
            showNotification(title, body, mobileNumber)
            makeCall(mobileNumber, metaData)
        }
    }

    private fun handleSettingsChange(title: String, body: String) {
        val SETTINGS_NOTIFICATION_ID = 2002
        showNotification(title, body, null, SETTINGS_NOTIFICATION_ID)
        serviceScope.launch {
            Log.i("FCM", "Starting FetchConfigUseCase...")
            fetchConfigUseCase()
            serviceController.syncServicesWithConfig()
            Log.i("FCM", "FetchConfigUseCase and Service Sync completed")
        }
    }

    private fun makeCall(phoneNumber: String, metaData: String?) {
        try {
            if (metaData != null) {
                CallReceiver.setPendingDialMetaData(phoneNumber, metaData)
            }
            
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("FCM", "Error initiating call", e)
        }
    }

    private fun showNotification(title: String, body: String, phoneNumber: String? = null, notificationId: Int = NOTIFICATION_ID) {
        // Ensure channel exists in case of OS cleanup, though AppInitializer handles it
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                serviceController.syncServicesWithConfig() // This will trigger setupNotificationChannels
                // Alternatively, just create it here as a fallback
                val channel = NotificationChannel(CHANNEL_ID, "General Notifications", NotificationManager.IMPORTANCE_HIGH)
                notificationManager.createNotificationChannel(channel)
            }
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(notificationId, notification)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "New token: $token")
    }
}
