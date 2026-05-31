package com.callmap.agenttracker.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.callmap.agenttracker.R

/**
 * Foreground-service notification for location work.
 *
 * When the user disables notifications for the app (App info → Notifications off),
 * [areAppNotificationsEnabled] is false — the notification is not shown, but
 * [startForegroundSafely] must still be called so GPS fetch is allowed; tracking continues
 * via ephemeral FGS + alarms (same as your field workflow).
 */
object TrackingNotificationHelper {

    private const val TAG = "TrackingNotification"

    const val CHANNEL_ID = "location_tracking_silent_v1"
    const val NOTIFICATION_ID = 12345

    fun areAppNotificationsEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.areNotificationsEnabled()
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.tracking_notification_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = context.getString(R.string.tracking_notification_channel_desc)
            enableVibration(false)
            setSound(null, null)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
    }

    fun build(service: Service): Notification {
        ensureChannel(service)
        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(service.getString(R.string.tracking_notification_title))
            .setContentText(service.getString(R.string.tracking_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    /**
     * Promotes service to foreground. Succeeds even when app notifications are disabled in Settings.
     */
    fun startForegroundSafely(service: Service, serviceType: Int? = null): Boolean {
        val notificationsOn = areAppNotificationsEnabled(service)
        if (!notificationsOn) {
            Log.i(TAG, "App notifications disabled in Settings — FGS will run without visible notification")
        }
        return try {
            val notification = build(service)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && serviceType != null) {
                ServiceCompat.startForeground(
                    service,
                    NOTIFICATION_ID,
                    notification,
                    serviceType
                )
            } else {
                service.startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed (notificationsEnabled=$notificationsOn): ${e.message}", e)
            false
        }
    }
}
