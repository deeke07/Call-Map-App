package com.callmap.agenttracker

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.UserManager
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import com.callmap.agenttracker.data.manager.DeviceStateManager
import com.callmap.agenttracker.domain.manager.AppInitializer
import com.callmap.agenttracker.domain.manager.EventManager
import com.callmap.agenttracker.domain.manager.SyncManager
import com.callmap.agenttracker.util.TrackingLog
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AgentTrackerApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var appInitializer: AppInitializer

    @Inject
    lateinit var stateManager: DeviceStateManager

    @Inject
    lateinit var syncManager: SyncManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastNetworkInitMs = 0L

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(SvgDecoder.Factory())
            }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        
        val userManager = getSystemService(Context.USER_SERVICE) as? UserManager
        if (userManager != null && !userManager.isUserUnlocked) {
            Log.w("AgentTrackerApp", "Application created in Direct Boot mode. Deferring initialization.")
        } else {
            appInitializer.init()
        }

        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(networkRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                appScope.launch {
                    val userManager = getSystemService(Context.USER_SERVICE) as? UserManager
                    if (userManager != null && !userManager.isUserUnlocked) {
                        Log.w("AgentTrackerApp", "Network restored but user locked. Deferring init.")
                        return@launch
                    }

                    TrackingLog.d("AgentTrackerApp", "Network restored")
                    stateManager.trackBinaryState(
                        stateKey = "network_status",
                        isEnabled = true,
                        enabledEvent = EventManager.DEVICE_ONLINE,
                        disabledEvent = EventManager.DEVICE_OFFLINE
                    )
                    val now = System.currentTimeMillis()
                    if (now - lastNetworkInitMs < NETWORK_INIT_DEBOUNCE_MS) {
                        syncManager.triggerPendingSync()
                    } else {
                        lastNetworkInitMs = now
                        appInitializer.init()
                    }
                }
            }

            override fun onLost(network: Network) {
                appScope.launch {
                    stateManager.trackBinaryState(
                        stateKey = "network_status",
                        isEnabled = false,
                        enabledEvent = EventManager.DEVICE_ONLINE,
                        disabledEvent = EventManager.DEVICE_OFFLINE
                    )
                }
            }
        })
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    companion object {
        private const val NETWORK_INIT_DEBOUNCE_MS = 30_000L
    }
}
