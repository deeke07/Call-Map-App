package com.callmap.agenttracker.presentation.permissions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.callmap.agenttracker.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.callmap.agenttracker.presentation.components.AppLogoHeader
import com.callmap.agenttracker.presentation.components.PrimaryButton
import com.callmap.agenttracker.presentation.components.SetupHeader
import com.callmap.agenttracker.presentation.components.PermissionSectionHeader
import com.callmap.agenttracker.presentation.components.PermissionCard
import com.callmap.agenttracker.service.MyAccessibilityService
import com.callmap.agenttracker.ui.theme.DarkBackground
import com.callmap.agenttracker.ui.theme.NeonLime

@Composable
fun PermissionsScreen(
    onAllPermissionsGranted: () -> Unit,
    viewModel: PermissionsViewModel = hiltViewModel()
) {
    val step by viewModel.currentStep

    LaunchedEffect(step) {
        if (step == PermissionStep.COMPLETED) {
            onAllPermissionsGranted()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Simple glow effect in background
        Box(
            modifier = Modifier
                .size(300.dp)
                .offset(x = (-100).dp, y = (-100).dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(NeonLime.copy(alpha = 0.15f), Color.Transparent)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            AppLogoHeader(height = 60)
            
            when (step) {
                PermissionStep.RUNTIME -> RuntimePermissionStep(onNext = viewModel::nextStep)
                PermissionStep.SPECIAL -> SpecialPermissionStep(onNext = viewModel::nextStep)
                PermissionStep.COMPLETED -> Unit
            }
        }
    }
}

@Composable
fun RuntimePermissionStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var permissionsState by remember {
        mutableStateOf(
            PermissionManager.runtimePermissions.associateWith {
                PermissionManager.isPermissionGranted(context, it)
            }
        )
    }

    // Refresh when returning to the app
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionsState = PermissionManager.runtimePermissions.associateWith {
                    PermissionManager.isPermissionGranted(context, it)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsState = permissionsState + result
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SetupHeader(title = "App Setup")
        
        PermissionSectionHeader(
            icon = Icons.Default.Shield,
            title = "Runtime Permissions",
            subtitle = "These are basic permissions needed for core app functions."
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(PermissionManager.runtimePermissions) { permission ->
                val name = permission.substringAfterLast(".").replace("_", " ")
                PermissionCard(
                    title = name,
                    icon = when {
                        permission.contains("RECORD_AUDIO") -> Icons.Default.Mic
                        permission.contains("LOCATION") -> Icons.Default.Shield
                        permission.contains("NOTIFICATIONS") -> Icons.Default.Notifications
                        else -> Icons.Default.Shield
                    },
                    isGranted = permissionsState[permission] ?: false,
                    onClick = {
                        launcher.launch(arrayOf(permission))
                    }
                )
            }
        }

        val allGranted = PermissionManager.runtimePermissions.all {
            permissionsState[it] == true || PermissionManager.isPermissionGranted(context, it)
        }
        val missingPermissions = permissionsState.filter { !it.value }.keys.toTypedArray()

        PrimaryButton(
            text = if (allGranted) "Continue" else "Grant All Permissions",
            onClick = {
                if (allGranted) {
                    onNext()
                } else {
                    launcher.launch(missingPermissions)
                }
            },
            modifier = Modifier.padding(bottom = 24.dp)
        )
    }
}

@Composable
fun SpecialPermissionStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isAccessibilityEnabled by remember {
        mutableStateOf(SpecialPermissionManager.isAccessibilityServiceEnabled(context, MyAccessibilityService::class.java))
    }
    var isBatteryOptimized by remember {
        mutableStateOf(SpecialPermissionManager.isBatteryOptimizationIgnored(context))
    }
    var isStorageManager by remember {
        mutableStateOf(SpecialPermissionManager.isManageExternalStorageGranted(context))
    }
    var isLocationEnabled by remember {
        mutableStateOf(SpecialPermissionManager.isLocationHardwareEnabled(context))
    }
    var isBackgroundLocationGranted by remember {
        mutableStateOf(PermissionManager.isBackgroundLocationGranted(context))
    }

    val bgLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        isBackgroundLocationGranted = granted
    }

    // Refresh state when returning to app
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = SpecialPermissionManager.isAccessibilityServiceEnabled(context, MyAccessibilityService::class.java)
                isBatteryOptimized = SpecialPermissionManager.isBatteryOptimizationIgnored(context)
                isStorageManager = SpecialPermissionManager.isManageExternalStorageGranted(context)
                isLocationEnabled = SpecialPermissionManager.isLocationHardwareEnabled(context)
                isBackgroundLocationGranted = PermissionManager.isBackgroundLocationGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SetupHeader(title = "App Setup")

        PermissionSectionHeader(
            icon = Icons.Default.Shield,
            title = "Special Permissions",
            subtitle = "Required for background reliability and call monitoring."
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                PermissionCard(
                    title = "Accessibility Service",
                    icon = Icons.Default.Hearing,
                    isGranted = isAccessibilityEnabled,
                    onClick = { SpecialPermissionManager.openAccessibilitySettings(context) }
                )
            }

            item {
                PermissionCard(
                    title = "Ignore Battery Optimization",
                    icon = Icons.Default.Shield,
                    isGranted = isBatteryOptimized,
                    onClick = { SpecialPermissionManager.requestIgnoreBatteryOptimization(context) }
                )
            }

            item {
                PermissionCard(
                    title = "All Files Access (Recordings)",
                    icon = Icons.Default.Shield,
                    isGranted = isStorageManager,
                    onClick = { SpecialPermissionManager.openManageExternalStorageSettings(context) }
                )
            }

            item {
                PermissionCard(
                    title = "Background Location",
                    subtitle = "Set to 'Allow all the time'",
                    icon = Icons.Default.Shield,
                    isGranted = isBackgroundLocationGranted,
                    onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            bgLocationLauncher.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                    }
                )
            }

            if (!isLocationEnabled) {
                item {
                    PermissionCard(
                        title = "Device Location (GPS)",
                        icon = Icons.Default.Shield,
                        isGranted = false,
                        onClick = { SpecialPermissionManager.openLocationSettings(context) }
                    )
                }
            }
        }

        val allGranted = isAccessibilityEnabled && isBatteryOptimized && isStorageManager && isLocationEnabled && isBackgroundLocationGranted
        
        PrimaryButton(
            text = "Finish Setup",
            onClick = onNext,
            enabled = allGranted,
            modifier = Modifier.padding(bottom = 24.dp)
        )
    }
}

