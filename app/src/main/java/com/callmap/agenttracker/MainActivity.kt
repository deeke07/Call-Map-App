package com.callmap.agenttracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.callmap.agenttracker.presentation.MainViewModel
import com.callmap.agenttracker.presentation.home.HomeScreen
import com.callmap.agenttracker.presentation.register_device.RegisterDeviceScreen
import com.callmap.agenttracker.presentation.permissions.PermissionsScreen
import com.callmap.agenttracker.ui.theme.AgentTrackerMobileAppTheme
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w("MainActivity", "Fetching FCM registration token failed", task.exception)
                return@addOnCompleteListener
            }
            Log.i("MainActivity", "FCM Token: ${task.result}")
        }

        enableEdgeToEdge()
        setContent {
            AgentTrackerMobileAppTheme {
                val viewModel: MainViewModel = hiltViewModel()
                val startDestination by viewModel.startDestination.collectAsState()
                val navController = rememberNavController()

                viewModel.checkState(this)

                startDestination?.let {
                    NavHost(
                        navController = navController,
                        startDestination = it
                    ) {
                        composable("register") {
                            RegisterDeviceScreen(
                                onRegistrationSuccess = {
                                    navController.navigate("permissions") {
                                        popUpTo("register") { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable("permissions") {
                            PermissionsScreen(
                                onAllPermissionsGranted = {
                                    navController.navigate("home") {
                                        popUpTo("permissions") { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable("home") {
                            HomeScreen()
                        }
                    }
                }
            }
        }
    }
}
