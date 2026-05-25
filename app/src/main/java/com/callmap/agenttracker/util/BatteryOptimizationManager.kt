package com.callmap.agenttracker.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages battery optimization detection and user prompts.
 * Helps ensure the app can run in the background without being killed by battery saver.
 */
@Singleton
class BatteryOptimizationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "BatteryOptimization"
    }

    /**
     * Check if the app is currently exempted from battery optimization.
     */
    fun isAppExemptFromBatteryOptimization(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
            } else {
                true // Pre-Android 6.0 doesn't have battery optimization
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking battery optimization status", e)
            false
        }
    }

    /**
     * Check if device is currently in battery saver / doze mode.
     */
    fun isDeviceInBatterySaverMode(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                powerManager?.isPowerSaveMode ?: false
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking battery saver mode", e)
            false
        }
    }

    /**
     * Get the detailed status of battery optimization.
     */
    fun getBatteryOptimizationStatus(): BatteryStatus {
        val isExempt = isAppExemptFromBatteryOptimization()
        val isSaverMode = isDeviceInBatterySaverMode()

        return BatteryStatus(
            isAppExempt = isExempt,
            isDeviceInSaverMode = isSaverMode,
            recommendation = when {
                !isExempt -> OptimizationRecommendation.NEED_EXEMPTION
                isSaverMode -> OptimizationRecommendation.SAVER_MODE_ACTIVE
                else -> OptimizationRecommendation.OK
            }
        )
    }

    /**
     * Create an intent to open the battery optimization settings for this app.
     * User can then manually add the app to the exemption list.
     */
    fun getRequestExemptionIntent(): Intent? {
        return try {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating exemption intent", e)
            null
        }
    }

    /**
     * Open battery optimization settings page for manual exemption.
     */
    fun openBatteryOptimizationSettings(): Intent? {
        return try {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating settings intent", e)
            null
        }
    }

    data class BatteryStatus(
        val isAppExempt: Boolean,
        val isDeviceInSaverMode: Boolean,
        val recommendation: OptimizationRecommendation
    )

    enum class OptimizationRecommendation {
        OK,                    // App is exempt and device not in saver mode
        NEED_EXEMPTION,        // App needs to be exempted from battery optimization
        SAVER_MODE_ACTIVE     // Device is in battery saver mode (temporary)
    }
}


