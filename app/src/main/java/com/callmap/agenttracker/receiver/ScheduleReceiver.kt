package com.callmap.agenttracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.callmap.agenttracker.domain.manager.SyncManager
import com.callmap.agenttracker.service.LocationService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ScheduleReceiver : BroadcastReceiver() {

    @Inject
    lateinit var syncManager: SyncManager

    companion object {
        const val ACTION_LOCATION_ALARM = "com.callmap.agenttracker.ACTION_LOCATION_ALARM"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i("ScheduleReceiver", "Alarm Received! Action: $action")

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScheduleReceiver::WakeLock")
        wakeLock.acquire(10000L) // 10 seconds is plenty to start a service

        when (action) {
            ACTION_LOCATION_ALARM -> {
                Log.i("ScheduleReceiver", "Triggering LocationService from Alarm.")
                val serviceIntent = Intent(context, LocationService::class.java).apply {
                    this.action = LocationService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
            else -> {
                Log.i("ScheduleReceiver", "Triggering Tracking Audit.")
                syncManager.scheduleTrackingAudit()
            }
        }
    }
}
