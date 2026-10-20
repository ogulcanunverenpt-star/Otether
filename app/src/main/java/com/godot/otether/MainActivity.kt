package com.godot.otether

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.godot.otether.service.TetherService
import com.godot.otether.ui.theme.OtetherTheme

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Permissions handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkAndRequestPermissions()

        setContent {
            OtetherTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF050505)
                ) {
                    MainScreen()
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.CHANGE_NETWORK_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }
}

@Suppress("DEPRECATION")
@Composable
fun MainScreen() {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(TetherService.isRunning) }
    var selectedSource by remember { mutableStateOf(TetherService.selectedSource) }
    var currentStep by remember { mutableIntStateOf(if (TetherService.isRunning) 3 else 1) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050505))
            .statusBarsPadding()
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Step Indicator Header
        StepProgressHeader(
            currentStep = currentStep,
            onStepClick = { step ->
                if (!isRunning && step < currentStep) {
                    currentStep = step
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        when (currentStep) {
            1 -> Step1SourceSelection(
                selectedSource = selectedSource,
                onSourceSelected = { selectedSource = it },
                onNextStep = { currentStep = 2 }
            )
            2 -> Step2SetupAndStart(
                selectedSource = selectedSource,
                onBackStep = { currentStep = 1 },
                onStartTethering = {
                    val intent = Intent(context, TetherService::class.java).apply {
                        putExtra("source", selectedSource)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    isRunning = true
                    currentStep = 3
                    webViewRef?.reload()
                }
            )
            3 -> Step3ActiveDashboard(
                selectedSource = selectedSource,
                isRunning = isRunning,
                onStopTethering = {
                    val intent = Intent(context, TetherService::class.java)
                    context.stopService(intent)
                    isRunning = false
                    currentStep = 1
                    webViewRef?.reload()
                },
                onConfigureAgain = {
                    currentStep = 1
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Embedded WebView Dashboard loading local assets
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(AndroidColor.parseColor("#050505"))
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccessFromFileURLs = true
                    settings.allowUniversalAccessFromFileURLs = true
                    loadUrl("file:///android_asset/web/index.html")
                    webViewRef = this
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}

@Composable
fun StepProgressHeader(
    currentStep: Int,
    onStepClick: (Int) -> Unit
) {
    val steps = listOf("1. Source", "2. Setup", "3. Dashboard")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF121212), shape = RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, title ->
            val stepNumber = index + 1
            val isActive = currentStep == stepNumber
            val isCompleted = currentStep > stepNumber

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(enabled = stepNumber < currentStep) {
                    onStepClick(stepNumber)
                }
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            color = when {
                                isActive -> Color(0xFFE50914)
                                isCompleted -> Color(0xFF2E7D32)
                                else -> Color(0xFF2A2A2A)
                            },
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isCompleted) "✓" else "$stepNumber",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    color = when {
                        isActive -> Color.White
                        isCompleted -> Color(0xFF81C784)
                        else -> Color(0xFF777777)
                    },
                    fontSize = 12.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                )
            }

            if (index < steps.size - 1) {
                Text(
                    text = "➔",
                    color = Color(0xFF444444),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun Step1SourceSelection(
    selectedSource: String,
    onSourceSelected: (String) -> Unit,
    onNextStep: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Step 1: Choose Upstream Internet Source",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Select where Otether will source its internet connection:",
                color = Color(0xFFAAAAAA),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            // Cellular Option Card
            SourceOptionCard(
                title = "📡 Cellular Data (Mobile 4G/5G)",
                description = "Share your mobile data plan with connected devices via Hotspot & Proxy.",
                isSelected = selectedSource == "cellular",
                onClick = { onSourceSelected("cellular") }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Wi-Fi Option Card
            SourceOptionCard(
                title = "📶 Wi-Fi Repeater (Extend Active Wi-Fi)",
                description = "Extend your active Wi-Fi connection to other devices using NetShare Wi-Fi Direct.",
                isSelected = selectedSource == "wifi",
                onClick = { onSourceSelected("wifi") }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onNextStep,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE50914),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("NEXT (Step 2) ➔", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SourceOptionCard(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) Color(0xFF220A0B) else Color(0xFF1A1A1A),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) Color(0xFFE50914) else Color(0xFF333333)
        )
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = Color(0xFFE50914),
                    unselectedColor = Color(0xFF666666)
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = description,
                    color = Color(0xFF999999),
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
fun Step2SetupAndStart(
    selectedSource: String,
    onBackStep: () -> Unit,
    onStartTethering: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Step 2: Confirm & Launch Tethering",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))

            // Summary Info Box
            val proxyIp = com.godot.otether.wifi.HotspotManager.getP2pIpAddress()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A), shape = RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = "Selected Source: " + if (selectedSource == "cellular") "📡 Cellular Data (Mobile 4G/5G)" else "📶 Wi-Fi Repeater Mode",
                    color = Color(0xFFFFD54F),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "• HTTP/HTTPS Proxy Address: $proxyIp:8080\n• Web Control Dashboard: http://$proxyIp:8081",
                    color = Color(0xFFAAAAAA),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Guidance Note
            Text(
                text = if (selectedSource == "wifi") {
                    "💡 Tip: For Wi-Fi Repeater mode, Wi-Fi & GPS must be ON. Connected devices (PC, TV, phones) must configure HTTP Proxy to $proxyIp:8080."
                } else {
                    "💡 Tip: For Cellular Data mode, ensure Mobile Data is ON. Connected devices (PC, TV, phones) must configure HTTP Proxy to $proxyIp:8080."
                },
                color = Color(0xFF81D4FA),
                fontSize = 11.sp,
                lineHeight = 15.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onBackStep,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF444444)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("⬅ Back")
                }

                Button(
                    onClick = onStartTethering,
                    modifier = Modifier.weight(2f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE50914),
                        contentColor = Color.White
                    )
                ) {
                    Text("START TETHERING", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun Step3ActiveDashboard(
    selectedSource: String,
    isRunning: Boolean,
    onStopTethering: () -> Unit,
    onConfigureAgain: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = if (isRunning) Color(0xFF00E676) else Color(0xFFFF1744),
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isRunning) "TETHERING ACTIVE" else "STOPPED",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = if (selectedSource == "cellular") "📡 Cellular Data" else "📶 Wi-Fi Repeater",
                    color = Color(0xFFAAAAAA),
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isRunning) {
                    Button(
                        onClick = onStopTethering,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF222222),
                            contentColor = Color(0xFFFF1744)
                        )
                    ) {
                        Text("⏹ STOP TETHERING", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = onConfigureAgain,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE50914),
                            contentColor = Color.White
                        )
                    ) {
                        Text("🔄 RECONFIGURE (Step 1)", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
