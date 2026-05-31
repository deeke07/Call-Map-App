package com.callmap.agenttracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.callmap.agenttracker.domain.manager.SyncManager
import com.callmap.agenttracker.service.LocationService
import com.callmap.agenttracker.util.TrackingLog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ScheduleReceiver : BroadcastReceiver() {

    @Inject
    lateinit var syncManager: SyncManager

    companion object {
        const val ACTION_LOCATION_ALARM = "com.callmap.agenttracker.ACTION_LOCATION_ALARM"
        const val ACTION_SCHEDULE_AUDIT = "com.callmap.agenttracker.ACTION_SCHEDULE_AUDIT"
        private const val TAG = "ScheduleReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        TrackingLog.d(TAG, "Broadcast: $action")

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "ScheduleReceiver::WakeLock"
        )
        // FIXED Bug 5: 60s — receiver only needs to start service, not do the fetch
        // Service acquires its own FetchLock for the actual location work
        wakeLock.acquire(60_000L)

        try {
            when (action) {
                ACTION_LOCATION_ALARM -> {
                    TrackingLog.i(TAG, "Location alarm — starting service")
                    startLocationService(context)
                }
                ACTION_SCHEDULE_AUDIT -> syncManager.scheduleTrackingAudit()
                "WATCHDOG_CHECK" -> syncManager.scheduleTrackingAudit()
                else -> syncManager.scheduleTrackingAudit()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onReceive", e)
        } finally {
            try {
                if (wakeLock.isHeld) wakeLock.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing WakeLock", e)
            }
        }
    }

    private fun startLocationService(context: Context) {
        try {
            val serviceIntent = Intent(context, LocationService::class.java).apply {
                action = LocationService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting LocationService", e)
        }
    }
}
