package com.callmap.agenttracker

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.callmap.agenttracker.presentation.MainViewModel
import com.callmap.agenttracker.presentation.home.HomeScreen
import com.callmap.agenttracker.presentation.permissions.PermissionsScreen
import com.callmap.agenttracker.presentation.register_device.RegisterDeviceScreen
import com.callmap.agenttracker.presentation.welcome.WelcomeScreen
import com.callmap.agenttracker.ui.theme.AgentTrackerMobileAppTheme
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

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
                val scope = rememberCoroutineScope()
                val context = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current

                // Monitor permissions and registration status whenever the app resumes
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            viewModel.checkState(context)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                // Enforce navigation if permission state or registration changes
                LaunchedEffect(startDestination) {
                    startDestination?.let { destination ->
                        val currentRoute = navController.currentBackStackEntry?.destination?.route
                        if (currentRoute != null && currentRoute != destination) {
                            // If permissions are revoked or we need to go to register, force it
                            if (destination == "permissions" || destination == "register" || destination == "welcome") {
                                navController.navigate(destination) {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        }
                    }
                }

                startDestination?.let {
                    NavHost(
                        navController = navController,
                        startDestination = it
                    ) {
                        composable("welcome") {
                            WelcomeScreen(
                                onGetStarted = {
                                    viewModel.completeWelcome(context)
                                },
                                onSignIn = {
                                    viewModel.completeWelcome(context)
                                }
                            )
                        }
                        composable("register") {
                            RegisterDeviceScreen(
                                onRegistrationSuccess = {
                                    navController.navigate("home") {
                                        popUpTo("register") { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable("permissions") {
                            PermissionsScreen(
                                onAllPermissionsGranted = {
                                    scope.launch {
                                        if (viewModel.isRegistered()) {
                                            navController.navigate("home") {
                                                popUpTo("permissions") { inclusive = true }
                                            }
                                        } else {
                                            navController.navigate("register") {
                                                popUpTo("permissions") { inclusive = true }
                                            }
                                        }
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
