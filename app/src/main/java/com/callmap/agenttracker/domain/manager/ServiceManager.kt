package com.callmap.agenttracker.domain.manager

import android.app.Notification
import android.app.Service

interface ServiceManager {
    fun startServices()
    fun stopServices()
    fun setupNotificationChannels()
    fun handleServiceLifecycle(trackingEnabled: Boolean)
    fun <T : Service> isServiceRunning(serviceClass: Class<T>): Boolean
    fun createNotification(content: String): Notification
}
