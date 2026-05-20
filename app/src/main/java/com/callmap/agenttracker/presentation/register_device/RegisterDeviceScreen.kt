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

                    if (qrEmail.isNotBlank() && qrPasscode.isNotBlank()) {
                        viewModel.onEmailChange(qrEmail)
                        viewModel.onPasscodeChange(qrPasscode)
                        viewModel.register()
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
        //onRegistrationSuccess()
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
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // 1. statusBarsPadding() ensures we don't overlap with time/battery
                // 2. navigationBarsPadding() ensures we don't overlap with the bottom pill/buttons
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Register Device",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Enter your credentials to link this device",
                fontSize = 16.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
            )

            OutlinedTextField(
                value = email,
                onValueChange = viewModel::onEmailChange,
                label = { Text("Agent Email") },
                placeholder = { Text("Enter your email") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                leadingIcon = {
                    Icon(Icons.Default.Person, contentDescription = null)
                },
                isError = state.passwordError != null,
                supportingText = {
                    if (state.passwordError != null) {
                        Text(text = state.passwordError!!)
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = passcode,
                onValueChange = viewModel::onPasscodeChange,
                label = { Text("Passcode") },
                placeholder = { Text("Enter your passcode") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                leadingIcon = {
                    Icon(Icons.Default.Lock, contentDescription = null)
                },
                isError = state.usernameError != null,
                supportingText = {
                    if (state.usernameError != null) {
                        Text(text = state.usernameError!!)
                    }
                },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = viewModel::register,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !state.isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "Register",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
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
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !state.isLoading
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = "Scan QR Code",
                    fontSize = 16.sp
                )
            }
        }
    }
}
