package com.callmap.agenttracker.presentation.register_device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import android.app.Activity
import com.callmap.agenttracker.presentation.register_device.components.QrScannerDialog
import org.json.JSONObject
import android.widget.Toast

import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import com.callmap.agenttracker.presentation.components.AppLogoHeader
import com.callmap.agenttracker.presentation.components.PrimaryButton
import com.callmap.agenttracker.presentation.components.SetupHeader
import com.callmap.agenttracker.ui.theme.DarkBackground
import com.callmap.agenttracker.ui.theme.NeonLime
import com.callmap.agenttracker.ui.theme.SurfaceDark
import com.callmap.agenttracker.ui.theme.TextPrimary
import com.callmap.agenttracker.ui.theme.TextSecondary
import com.callmap.agenttracker.ui.theme.BorderDark
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

@Composable
fun RegisterDeviceScreen(
    onRegistrationSuccess: () -> Unit,
    viewModel: RegisterDeviceViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val passcode by viewModel.passcode
    val email by viewModel.email
    val context = LocalContext.current

    var showQrScanner by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    
    // Handle back press to prevent accidental exit
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    BackHandler {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackPressTime < 2000) {
            (context as? Activity)?.finish()
        } else {
            lastBackPressTime = currentTime
            Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showQrScanner = true
        } else {
            Toast.makeText(context, "Camera permission is required to scan QR codes", Toast.LENGTH_SHORT).show()
        }
    }

    if (showQrScanner) {
        QrScannerDialog(
            onDismiss = { showQrScanner = false },
            onResult = { contents ->
                showQrScanner = false
                try {
                    val json = JSONObject(contents)
                    val qrEmail = json.optString("email")
                    val qrPasscode = json.optString("passcode")
                    val qrBaseUrl = json.optString("base_url").takeIf { it.isNotBlank() }

                    if (qrEmail.isNotBlank() && qrPasscode.isNotBlank()) {
                        viewModel.onEmailChange(qrEmail)
                        viewModel.onPasscodeChange(qrPasscode)
                        viewModel.register(qrBaseUrl)
                    } else {
                        Toast.makeText(context, "Invalid QR: Missing email or passcode", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Invalid QR format", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.success) {
        if (state.success != null) {
            onRegistrationSuccess()
        }
    }

    LaunchedEffect(state.error) {
        if (state.error.isNotBlank()) {
            snackbarHostState.showSnackbar(
                message = state.error,
                duration = SnackbarDuration.Short
            )
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = DarkBackground
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // Glow effect
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
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Spacer(modifier = Modifier.height(50.dp))
                AppLogoHeader(height = 70)
                
                Spacer(modifier = Modifier.height(48.dp))
                
                Text(
                    text = "Register Device",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
                Text(
                    text = "Enter your credentials to link this device",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = TextSecondary
                    ),
                    modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
                )

                TextField(
                    value = email,
                    onValueChange = viewModel::onEmailChange,
                    placeholder = { Text("Agent Email", color = TextSecondary) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = RoundedCornerShape(12.dp),
                    leadingIcon = {
                        Icon(Icons.Default.Person, contentDescription = null, tint = NeonLime)
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = SurfaceDark,
                        unfocusedContainerColor = SurfaceDark,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = NeonLime
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                TextField(
                    value = passcode,
                    onValueChange = viewModel::onPasscodeChange,
                    placeholder = { Text("Passcode", color = TextSecondary) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = RoundedCornerShape(12.dp),
                    leadingIcon = {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = NeonLime)
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = null,
                                tint = TextSecondary
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = SurfaceDark,
                        unfocusedContainerColor = SurfaceDark,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = NeonLime
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(32.dp))

                PrimaryButton(
                    text = "Register",
                    onClick = viewModel::register,
                    enabled = !state.isLoading
                )

                if (state.isLoading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    CircularProgressIndicator(color = NeonLime)
                }

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f).height(1.dp).background(BorderDark))
                    Text(
                        text = "or",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = TextSecondary
                    )
                    Box(modifier = Modifier.weight(1f).height(1.dp).background(BorderDark))
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = {
                        when (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)) {
                            PackageManager.PERMISSION_GRANTED -> {
                                showQrScanner = true
                            }
                            else -> {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = RoundedCornerShape(32.dp),
                    border = BorderStroke(1.dp, NeonLime),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonLime)
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = "Scan QR Code",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

