package com.callmap.agenttracker.presentation.permissions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.callmap.agenttracker.presentation.permissions.components.PermissionItem
import com.callmap.agenttracker.service.MyAccessibilityService

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("App Setup", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.background
        ) {
            when (step) {
                PermissionStep.EXPLANATION -> ExplanationStep(onNext = viewModel::nextStep)
                PermissionStep.RUNTIME -> RuntimePermissionStep(onNext = viewModel::nextStep)
                PermissionStep.SPECIAL -> SpecialPermissionStep(onNext = viewModel::nextStep)
                PermissionStep.COMPLETED -> Unit
            }
        }
    }
}

@Composable
fun ExplanationStep(onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Permissions Required",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "To provide accurate agent tracking and call logging, we need several permissions. We will guide you through them step-by-step.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("Start Setup")
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = "Runtime Permissions",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "These are basic permissions needed for core app functions.",
            modifier = Modifier.padding(vertical = 8.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(PermissionManager.runtimePermissions) { permission ->
                val name = permission.substringAfterLast(".").replace("_", " ")
                PermissionItem(
                    title = name,
                    isGranted = permissionsState[permission] ?: false,
                    onClick = {
                        launcher.launch(arrayOf(permission))
                    }
                )
            }
        }

        val allGranted = permissionsState.values.all { it }
        val missingPermissions = permissionsState.filter { !it.value }.keys.toTypedArray()

        Button(
            onClick = {
                if (allGranted) {
                    onNext()
                } else {
                    launcher.launch(missingPermissions)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (allGranted) MaterialTheme.colorScheme.primary else Color(0xFFE91E63),
                contentColor = Color.White
            )
        ) {
            Text(if (allGranted) "Continue" else "Grant All Permissions")
        }
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

    // Refresh state when returning to app
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = SpecialPermissionManager.isAccessibilityServiceEnabled(context, MyAccessibilityService::class.java)
                isBatteryOptimized = SpecialPermissionManager.isBatteryOptimizationIgnored(context)
                isStorageManager = SpecialPermissionManager.isManageExternalStorageGranted(context)
                isLocationEnabled = SpecialPermissionManager.isLocationHardwareEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = "Special Permissions",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Required for background reliability and call monitoring.",
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        PermissionItem(
            title = "Accessibility Service",
            isGranted = isAccessibilityEnabled,
            onClick = { SpecialPermissionManager.openAccessibilitySettings(context) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        PermissionItem(
            title = "Ignore Battery Optimization",
            isGranted = isBatteryOptimized,
            onClick = { SpecialPermissionManager.requestIgnoreBatteryOptimization(context) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        PermissionItem(
            title = "All Files Access (Recordings)",
            isGranted = isStorageManager,
            onClick = { SpecialPermissionManager.openManageExternalStorageSettings(context) }
        )

        if (!isLocationEnabled) {
            Spacer(modifier = Modifier.height(12.dp))
            PermissionItem(
                title = "Device Location (GPS)",
                isGranted = false,
                onClick = { SpecialPermissionManager.openLocationSettings(context) }
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Simple refresh button since these settings happen outside the app
        TextButton(
            onClick = {
                isAccessibilityEnabled = SpecialPermissionManager.isAccessibilityServiceEnabled(context, MyAccessibilityService::class.java)
                isBatteryOptimized = SpecialPermissionManager.isBatteryOptimizationIgnored(context)
                isStorageManager = SpecialPermissionManager.isManageExternalStorageGranted(context)
                isLocationEnabled = SpecialPermissionManager.isLocationHardwareEnabled(context)
            },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Refresh Status")
        }

        val allGranted = isAccessibilityEnabled && isBatteryOptimized && isStorageManager && isLocationEnabled
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = allGranted
        ) {
            Text("Finish Setup")
        }
    }
}
