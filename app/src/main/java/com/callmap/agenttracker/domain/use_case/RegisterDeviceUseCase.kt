package com.callmap.agenttracker.domain.use_case

import android.annotation.SuppressLint
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import com.callmap.agenttracker.BuildConfig
import com.callmap.agenttracker.data.remote.dto.DeviceRegistrationRequest
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.domain.model.RegistrationResult
import com.callmap.agenttracker.domain.repository.AuthRepository
import com.callmap.agenttracker.util.Resource
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

class RegisterDeviceUseCase @Inject constructor(
    private val repository: AuthRepository,
    private val sessionManager: SessionManager,
    @ApplicationContext private val context: Context
) {
    @SuppressLint("HardwareIds")
    operator fun invoke(passcode: String, email: String): Flow<Resource<RegistrationResult>> = flow {
        emit(Resource.Loading())

        //val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

        val appVersion = BuildConfig.VERSION_NAME
        
        val pushToken = try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: Exception) {
            null
        }

        val request = DeviceRegistrationRequest(
            passcode = passcode,
            userEmail = email,
            deviceName = "${Build.MODEL} - ${email.substringBefore("@")}",
            deviceToken = pushToken,
            platform = "android",
            deviceModel = Build.MODEL,
            appVersion = appVersion,
            batteryLevel = getBatteryLevel(context),
            pushToken = pushToken
        )

        val result = repository.registerDevice(request)
        emit(result)
    }

    fun getBatteryLevel(context: Context): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
}
