package com.callmap.agenttracker.domain.usecase

import android.util.Log
import com.callmap.agenttracker.domain.manager.ServiceManager
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.domain.manager.SyncManager
import com.callmap.agenttracker.domain.repository.AuthRepository
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class LogoutUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager,
    private val serviceManager: ServiceManager,
    private val syncManager: SyncManager
) {
    suspend operator fun invoke() {
        Log.w("LogoutUseCase", "Initiating global logout and cleanup...")

        try {
            val registration = sessionManager.getRegistration().first()
            if (registration != null) {
                // 1. Mark device as offline on backend
                Log.d("LogoutUseCase", "Marking device offline on backend...")
                authRepository.markDeviceOffline(registration.deviceUuid)
                
                // 2. Clear FCM token (optional but recommended)
                try {
                    FirebaseMessaging.getInstance().deleteToken()
                    Log.d("LogoutUseCase", "FCM Token deleted")
                } catch (e: Exception) {
                    Log.e("LogoutUseCase", "Failed to delete FCM token", e)
                }
            }

            // 3. Stop all background services
            Log.d("LogoutUseCase", "Stopping all services...")
            serviceManager.stopServices()

            // 4. Cancel all background workers and alarms
            Log.d("LogoutUseCase", "Cancelling all background work...")
            syncManager.cancelAllSync()

            // 5. Clear all session data (this triggers UI redirect via StateFlow)
            Log.d("LogoutUseCase", "Clearing session data...")
            sessionManager.clearSession()

            Log.i("LogoutUseCase", "Logout and cleanup completed successfully.")
        } catch (e: Exception) {
            Log.e("LogoutUseCase", "Error during logout process", e)
        }
    }
}
