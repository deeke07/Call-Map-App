package com.callmap.agenttracker.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.callmap.agenttracker.domain.model.RegistrationResult

import coil.compose.AsyncImage
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import com.callmap.agenttracker.presentation.permissions.AccessibilityStatus

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PhoneIphone
import com.callmap.agenttracker.presentation.components.AppLogoHeader
import com.callmap.agenttracker.presentation.components.SetupHeader
import com.callmap.agenttracker.ui.theme.DarkBackground
import com.callmap.agenttracker.ui.theme.NeonLime
import com.callmap.agenttracker.ui.theme.SurfaceDark
import com.callmap.agenttracker.ui.theme.TextPrimary
import com.callmap.agenttracker.ui.theme.TextSecondary
import com.callmap.agenttracker.ui.theme.BorderDark
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign

import androidx.compose.ui.tooling.preview.Preview
import com.callmap.agenttracker.ui.theme.AgentTrackerMobileAppTheme

@Composable
fun HomeContent(
    homeState: HomeState,
    onOpenLocationSettings: () -> Unit = {},
    onOpenAccessibilitySettings: () -> Unit = {}
) {
    val data = homeState.registration

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            if (homeState.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(2.dp),
                    color = NeonLime,
                    trackColor = Color.Transparent
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)) {
            // Glow effect
            Box(
                modifier = Modifier
                    .size(400.dp)
                    .offset(x = (-150).dp, y = (-150).dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(NeonLime.copy(alpha = 0.15f), Color.Transparent)
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (homeState.isLoading) Modifier else Modifier.statusBarsPadding())
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppLogoHeader(height = 60)
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceDark)
                            .border(BorderStroke(1.dp, BorderDark), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = NeonLime,
                            modifier = Modifier.size(20.dp)
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color.Red)
                                .align(Alignment.Center)
                                .offset(x = 6.dp, y = (-6).dp)
                                .border(BorderStroke(1.dp, SurfaceDark), CircleShape)
                        )
                    }
                }

              //  Spacer(modifier = Modifier.height(12.dp))

                SetupHeader(title = "Agent Dashboard")

                Spacer(modifier = Modifier.height(24.dp))

                // Profile Section
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .border(BorderStroke(2.dp, NeonLime), CircleShape)
                        .padding(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(SurfaceDark),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!data?.agentProfile.isNullOrEmpty()) {
                            AsyncImage(
                                model = data.agentProfile,
                                contentDescription = "Profile Image",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(80.dp),
                                tint = NeonLime
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                data?.agentName?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                }
                data?.agentEmail?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = TextSecondary
                        )
                    )
                }

                Spacer(modifier = Modifier.height(48.dp))

                // Device Cards
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = SurfaceDark,
                    border = BorderStroke(1.dp, BorderDark)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(NeonLime.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.PhoneIphone, null, tint = NeonLime, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "Device Information",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Icon(Icons.Default.ChevronRight, null, tint = NeonLime)
                        }
                        
                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = BorderDark)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(TextSecondary.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.PhoneIphone, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = data?.deviceName ?: "Unknown Device",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                )
                                Text(
                                    text = data?.agentEmail?.substringBefore("@") ?: "agent",
                                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                                )
                            }
                            
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = NeonLime.copy(alpha = 0.1f),
                                border = BorderStroke(1.dp, NeonLime.copy(alpha = 0.2f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(NeonLime))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Connected",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = NeonLime,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                if (homeState.accessibilityStatus != AccessibilityStatus.CONNECTED) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = SurfaceDark,
                        border = BorderStroke(1.dp, BorderDark)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = when (homeState.accessibilityStatus) {
                                    AccessibilityStatus.DISABLED -> "Accessibility needs attention"
                                    AccessibilityStatus.RECONNECTING -> "Accessibility reconnecting"
                                    else -> "Checking accessibility"
                                },
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = when (homeState.accessibilityStatus) {
                                    AccessibilityStatus.DISABLED -> "You are still signed in. Enable CallMap in Accessibility settings to restore its additional background recovery support."
                                    AccessibilityStatus.RECONNECTING -> "You are still signed in. Accessibility is enabled; waiting for Android to reconnect it. If this persists, check CallMap in Accessibility settings."
                                    else -> "You are still signed in. Waiting for accessibility status."
                                },
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = onOpenAccessibilitySettings) {
                                Text("Accessibility settings", color = NeonLime)
                            }
                        }
                    }
                }

                if (!homeState.isLocationEnabled || !homeState.isLocationPermissionGranted) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.2f))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (!homeState.isLocationPermissionGranted) "Permission Denied" else "Location Disabled",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                )
                                Text(
                                    text = if (!homeState.isLocationPermissionGranted) 
                                        "Grant location permission to enable tracking." 
                                        else "Enable GPS to ensure tracking works correctly.",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                    )
                                )
                            }
                            TextButton(onClick = onOpenLocationSettings) {
                                Text("Fix", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Preview
@Composable
fun HomeScreenLoadingPreview() {
    AgentTrackerMobileAppTheme {
        HomeContent(
            homeState = HomeState(
                isLoading = true,
                registration = RegistrationResult(
                    deviceUuid = "uuid-123",
                    deviceName = "Pixel 6",
                    recordingEnabled = true,
                    trackingEnabled = true,
                    locationFrequency = 300,
                    agentName = "John Doe",
                    agentEmail = "john@example.com",
                    agentProfile = ""
                )
            )
        )
    }
}

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel()
) {
    val homeState by viewModel.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Binding can finish after ON_RESUME. Refresh while visible without restarting services.
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.checkLocationStatus()
                delay(2_000)
            }
        }
    }

    // Refresh status and config when returning to app
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkLocationStatus()
                viewModel.refreshConfig()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    HomeContent(
        homeState = homeState,
        onOpenLocationSettings = { viewModel.openLocationSettings() },
        onOpenAccessibilitySettings = { viewModel.openAccessibilitySettings() }
    )
}

