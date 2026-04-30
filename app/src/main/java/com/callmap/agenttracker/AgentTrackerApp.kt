package com.callmap.agenttracker

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import com.callmap.agenttracker.data.manager.DeviceStateManager
import com.callmap.agenttracker.domain.manager.AppInitializer
import com.callmap.agenttracker.domain.manager.EventManager
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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(SvgDecoder.Factory())
            }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        appInitializer.init()
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
                    Log.d("AgentTrackerApp", "Network restored. Tracking Online event.")
                    stateManager.trackBinaryState(
                        stateKey = "network_status",
                        isEnabled = true,
                        enabledEvent = EventManager.DEVICE_ONLINE,
                        disabledEvent = EventManager.DEVICE_OFFLINE
                    )
                    // No manual sync call needed here. 
                    // WorkManager will automatically resume any pending sync tasks 
                    // that were waiting for a network connection.
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
}
