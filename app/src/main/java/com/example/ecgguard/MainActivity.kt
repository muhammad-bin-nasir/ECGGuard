package com.example.ecgguard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import java.net.HttpURLConnection
import java.net.URL
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import androidx.compose.animation.core.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var streamManager: BleStreamManager? = null
    private var model: ECGModel? = null

    @SuppressLint("MissingPermission")
    private fun sendWhatsAppAlerts(contacts: List<EmergencyContact>) {
        if (contacts.isEmpty()) return

        var locationText = "(Location unavailable)"
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val location = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { provider ->
                    try { lm.getLastKnownLocation(provider) } catch (e: Exception) { null }
                }
                .maxByOrNull { it.time }
            if (location != null) {
                locationText = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
            }
        } catch (e: Exception) { /* ignore */ }

        val text = "ECGGuard ALERT: Cardiac anomaly detected! Immediate attention may be needed. Location: $locationText"

        // CallMeBot free WhatsApp API — no paid plan needed.
        // Each contact must activate it once by messaging +34 644 56 59 11 on WhatsApp:
        //   "I allow callmebot to send me messages"
        // then saving the API key they receive.
        contacts.forEach { contact ->
            if (contact.apiKey.isBlank()) return@forEach
            Thread {
                try {
                    val encoded = java.net.URLEncoder.encode(text, "UTF-8")
                    val urlStr = "https://api.callmebot.com/whatsapp.php" +
                        "?phone=${contact.phone}&text=$encoded&apikey=${contact.apiKey}"
                    val conn = URL(urlStr).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.responseCode  // execute
                    conn.disconnect()
                } catch (e: Exception) { /* network error */ }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            model = ECGModel(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setContent {
            MaterialTheme {
                Surface(color = Color(0xFF0D1117), contentColor = Color.White) {
                    MainApp()
                }
            }
        }
    }

    @Composable
    fun MainApp() {
        // --- SHARED STATE ---
        var currentScreen by remember { mutableStateOf("HOME") }

        var logs by remember { mutableStateOf("System Initialized.\nWaiting for user...") }
        var mseDisplay by remember { mutableStateOf("0.0000") }
        var latencyDisplay by remember { mutableStateOf("0 ms") }
        var heartRateDisplay by remember { mutableStateOf("-- BPM") }
        var statusDisplay by remember { mutableStateOf("AWAITING CONNECTION") }
        var statusColor by remember { mutableStateOf(Color.DarkGray) }

        // State for the graph
        var graphData by remember { mutableStateOf(FloatArray(0)) }
        var isBuffering by remember { mutableStateOf(true) }

        // --- EMERGENCY / ANOMALY ALERT STATE ---
        var showAnomalyDialog by remember { mutableStateOf(false) }
        var anomalyEpisodeHandled by remember { mutableStateOf(false) }
        var countdownSeconds by remember { mutableStateOf(10) }
        var contacts by remember { mutableStateOf(EmergencyContactStore.getContacts(this@MainActivity)) }

        // Trigger dialog on first detection of each anomaly episode
        LaunchedEffect(statusDisplay) {
            if (statusDisplay == "ANOMALY DETECTED" && !anomalyEpisodeHandled) {
                showAnomalyDialog = true
                anomalyEpisodeHandled = true
            }
            if (statusDisplay == "NORMAL RHYTHM") {
                anomalyEpisodeHandled = false  // reset for next episode
            }
        }

        // Countdown: auto-send alert if user doesn't respond in 10 seconds
        LaunchedEffect(showAnomalyDialog) {
            if (showAnomalyDialog) {
                countdownSeconds = 10
                while (showAnomalyDialog && countdownSeconds > 0) {
                    delay(1000L)
                    if (showAnomalyDialog) countdownSeconds--
                }
                if (showAnomalyDialog) {
                    showAnomalyDialog = false
                    sendWhatsAppAlerts(contacts)
                }
            }
        }

        // Show anomaly dialog on top of whatever screen is active
        if (showAnomalyDialog) {
            AnomalyAlertDialog(
                countdown = countdownSeconds,
                hasContacts = contacts.isNotEmpty(),
                onFineClick = { showAnomalyDialog = false },
                onAlertClick = {
                    showAnomalyDialog = false
                    sendWhatsAppAlerts(contacts)
                }
            )
        }

        val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { perms ->
            if (perms.values.all { it }) {
                logs += "\nPermissions Granted. Connecting..."
                streamManager?.connect()
                statusDisplay = "BUFFERING SIGNAL (10s)..."
                statusColor = Color(0xFFE67E22) // Orange
                isBuffering = true
            } else {
                logs += "\nPermissions Denied."
            }
        }

        // --- BACKGROUND MANAGER ---
        if (streamManager == null) {
            streamManager = BleStreamManager(
                context = this,
                onLog = { msg -> runOnUiThread { logs += "\n$msg" } },
                onDataReceived = { inputData, _ ->

                    val tStart = System.nanoTime()

                    val mvData = FloatArray(inputData.size) { i -> inputData[i] * 0.001f }
                    val cleanData = SignalProcessor.cleanSignal(mvData)

                    if (!SignalProcessor.isMechanicallySound(cleanData)) {
                        runOnUiThread {
                            statusDisplay = "ARTIFACT / MOTION DETECTED"
                            statusColor = Color.DarkGray
                            logs += "\n[Warning] Signal dropped by Gatekeeper."
                        }
                        return@BleStreamManager
                    }

                    val mu = cleanData.average().toFloat()
                    val centeredData = FloatArray(cleanData.size) { i -> cleanData[i] - mu }

                    var finalMse = 0f
                    var isStructural = true

                    if (model != null) {
                        try {
                            val (rawMse, reconstructedData) = model!!.runInference(centeredData)
                            isStructural = SignalProcessor.checkStructuralError(centeredData, reconstructedData, rawMse)
                            // Suppress MSE only when the high error is caused by a structural/lead
                            // artifact (isStructural=true), NOT when it is a genuine cardiac anomaly.
                            finalMse = if (rawMse > 0.30f && isStructural) 0.0f else rawMse
                        } catch (e: Exception) {
                            logs += "\nModel Error: ${e.message}"
                        }
                    }

                    val tEnd = System.nanoTime()
                    val inferenceTimeMs = (tEnd - tStart) / 1_000_000.0

                    val heartRate = SignalProcessor.calculateHeartRate(centeredData)

                    // --- UPDATE UI STATE ---
                    runOnUiThread {
                        isBuffering = false
                        mseDisplay = String.format("%.4f", finalMse)
                        latencyDisplay = String.format("%.1f ms", inferenceTimeMs)
                        heartRateDisplay = if (heartRate in 30..220) "$heartRate BPM" else "-- BPM"
                        logs += "\n[Prediction] MSE: $mseDisplay | HR: $heartRateDisplay | Structural: $isStructural"

                        // Extract the last 500 samples (2 seconds) for a clean visual plot
                        graphData = if (centeredData.size >= 500) {
                            centeredData.copyOfRange(centeredData.size - 500, centeredData.size)
                        } else {
                            centeredData
                        }

                        if (finalMse > 0.30f) {
                            statusDisplay = "ANOMALY DETECTED"
                            statusColor = Color(0xFFE74C3C) // Red
                        } else {
                            statusDisplay = "NORMAL RHYTHM"
                            statusColor = Color(0xFF2ECC71) // Green
                        }
                    }
                }
            )
        }

        // --- NAVIGATION ROUTING ---
        // Added .systemBarsPadding() right here to fix the overlap issue!
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            // Custom Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ECGGuard", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Row {
                    // Contacts icon
                    IconButton(onClick = {
                        currentScreen = if (currentScreen == "CONTACTS") "HOME" else "CONTACTS"
                    }) {
                        Icon(
                            imageVector = if (currentScreen == "CONTACTS") Icons.Default.Close else Icons.Default.Person,
                            contentDescription = "Contacts",
                            tint = if (currentScreen == "CONTACTS") Color.White else Color(0xFF3498DB)
                        )
                    }
                    // Settings icon
                    IconButton(onClick = {
                        currentScreen = if (currentScreen == "SETTINGS") "HOME" else "SETTINGS"
                    }) {
                        Icon(
                            imageVector = if (currentScreen == "SETTINGS") Icons.Default.Close else Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color.White
                        )
                    }
                }
            }

            // Screen Content
            when (currentScreen) {
                "HOME" -> HomeScreen(statusDisplay, statusColor, mseDisplay, latencyDisplay, heartRateDisplay, graphData, isBuffering)
                "SETTINGS" -> SettingsScreen(logs, { permissionLauncher.launch(permissionsToRequest) })
                "CONTACTS" -> EmergencyContactsScreen(
                    contacts = contacts,
                    onContactsChanged = { updated ->
                        contacts = updated
                        EmergencyContactStore.saveContacts(this@MainActivity, updated)
                    }
                )
            }
        }
    }

    @Composable
    fun HomeScreen(
        statusDisplay: String, statusColor: Color,
        mseDisplay: String, latencyDisplay: String,
        heartRateDisplay: String,
        graphData: FloatArray, isBuffering: Boolean
    ) {
        val scrollState = rememberScrollState()

        // Infinite blinking animation used for both the status dot and LIVE badge dot
        val infiniteTransition = rememberInfiniteTransition(label = "status")
        val dotAlpha by infiniteTransition.animateFloat(
            initialValue = 0.25f, targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ), label = "dot"
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── 1. ANIMATED STATUS BANNER ─────────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(containerColor = statusColor),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier.fillMaxWidth().height(68.dp)
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(Color.White.copy(alpha = dotAlpha), CircleShape)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        statusDisplay,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        letterSpacing = 2.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── 2. LIVE ECG CHART ─────────────────────────────────────────────
            Box(Modifier.fillMaxWidth()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF020A02)),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    modifier = Modifier.fillMaxWidth().height(230.dp)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (isBuffering) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    color = Color(0xFF39FF14),
                                    strokeWidth = 3.dp
                                )
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    "Filling 10-Second Buffer…",
                                    color = Color(0xFF39FF14).copy(alpha = 0.7f),
                                    fontSize = 13.sp
                                )
                            }
                        } else {
                            ECGChart(graphData)
                        }
                    }
                }
                // Blinking LIVE badge (top-right corner of chart)
                if (!isBuffering) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFCC2200)),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 10.dp, end = 10.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(5.dp)
                                    .background(Color.White.copy(alpha = dotAlpha), CircleShape)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text("LIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 3. HEART RATE CARD ────────────────────────────────────────────
            HeartRateCard(heartRateDisplay)

            Spacer(Modifier.height(12.dp))

            // ── 4. AI METRICS ROW ─────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    title = "Recon. Error (MSE)",
                    value = mseDisplay,
                    accentColor = Color(0xFF3498DB),
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "AI Latency",
                    value = latencyDisplay,
                    accentColor = Color(0xFF9B59B6),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(4.dp))
        }
    }

    @Composable
    fun ECGChart(data: FloatArray) {
        if (data.isEmpty()) return

        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val width = size.width
            val height = size.height
            val majorGrid = Color(0xFF003300)
            val minorGrid = Color(0xFF001500)

            // Fine ECG-paper grid (20 columns, 10 rows; every 2nd line is major)
            for (i in 0..20) {
                val x = width * (i / 20f)
                drawLine(if (i % 2 == 0) majorGrid else minorGrid, Offset(x, 0f), Offset(x, height), strokeWidth = 0.6f)
            }
            for (i in 0..10) {
                val y = height * (i / 10f)
                drawLine(if (i % 2 == 0) majorGrid else minorGrid, Offset(0f, y), Offset(width, y), strokeWidth = 0.6f)
            }
            // Subtle centre baseline
            drawLine(Color(0xFF005000), Offset(0f, height / 2f), Offset(width, height / 2f), strokeWidth = 1f)

            // Scale data, leaving 5 % margin top & bottom
            val maxVal = data.maxOrNull() ?: 1f
            val minVal = data.minOrNull() ?: -1f
            val range = (maxVal - minVal).coerceAtLeast(0.1f)

            val path = Path()
            val stepX = width / (data.size - 1).coerceAtLeast(1)

            data.forEachIndexed { index, value ->
                val x = index * stepX
                val normalizedY = (value - minVal) / range
                val y = height - (normalizedY * height * 0.90f) - (height * 0.05f)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            // Neon-green glow: three layered strokes (outer → core)
            val ecgColor = Color(0xFF39FF14)
            drawPath(path, ecgColor.copy(alpha = 0.07f), style = Stroke(width = 16f))
            drawPath(path, ecgColor.copy(alpha = 0.18f), style = Stroke(width = 8f))
            drawPath(path, ecgColor.copy(alpha = 0.55f), style = Stroke(width = 3.5f))
            drawPath(path, ecgColor,                    style = Stroke(width = 1.8f))
        }
    }

    @Composable
    fun AnomalyAlertDialog(
        countdown: Int,
        hasContacts: Boolean,
        onFineClick: () -> Unit,
        onAlertClick: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = { /* Require explicit choice — no outside-tap dismiss */ },
            containerColor = Color(0xFF1A1A2E),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFE74C3C),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "ANOMALY DETECTED",
                        color = Color(0xFFE74C3C),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }
            },
            text = {
                Column {
                    Text(
                        "An irregular ECG pattern was detected. Did you recently perform intense exercise, experience heavy sweating, make sudden movements, or adjust the sensor?",
                        color = Color.White,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    if (!hasContacts) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2C1A00)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                "⚠ No emergency contacts saved. Add contacts using the \uD83D\uDC64 icon in the top bar.",
                                color = Color(0xFFF39C12),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp),
                                lineHeight = 18.sp
                            )
                        }
                    } else {
                        Text(
                            "Tap NO to immediately send an SMS with your location to all emergency contacts. No internet required.",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { countdown / 10f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFFE74C3C),
                        trackColor = Color(0xFF2C2C2C)
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "Auto-sending in $countdown s…",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onFineClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27AE60)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("YES, I'M FINE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                Button(
                    onClick = onAlertClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE74C3C)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("NO — SEND ALERT", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        )
    }

    @Composable
    fun EmergencyContactsScreen(
        contacts: List<EmergencyContact>,
        onContactsChanged: (List<EmergencyContact>) -> Unit
    ) {
        var nameInput by remember { mutableStateOf("") }
        var phoneInput by remember { mutableStateOf("") }
        var apiKeyInput by remember { mutableStateOf("") }
        val scrollState = rememberScrollState()
        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFF3498DB),
            unfocusedBorderColor = Color(0xFF444444),
            focusedLabelColor = Color(0xFF3498DB),
            unfocusedLabelColor = Color.Gray,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = Color.White
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            Text(
                "Emergency Contacts",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "When an anomaly is confirmed, your location will be sent to these contacts via WhatsApp.",
                color = Color.Gray,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(20.dp))

            // ── Add contact form ──────────────────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Add Contact", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Full Name") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = phoneInput,
                        onValueChange = { phoneInput = it },
                        label = { Text("WhatsApp Number (e.g. +923001234567)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        colors = fieldColors,
                        supportingText = {
                            Text(
                                "International format with + prefix.",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("CallMeBot API Key") },
                        singleLine = true,
                        colors = fieldColors,
                        supportingText = {
                            Text(
                                "Contact must WhatsApp +34 644 56 59 11 saying \"I allow callmebot to send me messages\" to get their key.",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            val name = nameInput.trim()
                            val phone = phoneInput.trim()
                            val apiKey = apiKeyInput.trim()
                            if (name.isNotEmpty() && phone.length >= 7 && apiKey.isNotEmpty()) {
                                onContactsChanged(contacts + EmergencyContact(name, phone, apiKey))
                                nameInput = ""
                                phoneInput = ""
                                apiKeyInput = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3498DB)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("ADD CONTACT", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Contacts list ─────────────────────────────────────────────────
            if (contacts.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF161B22), RoundedCornerShape(12.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No contacts saved yet.", color = Color.Gray, fontSize = 14.sp)
                }
            } else {
                Text(
                    "${contacts.size} contact${if (contacts.size > 1) "s" else ""} saved",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                contacts.forEachIndexed { index, contact ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .background(Color(0xFF1A3A5C), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    contact.name.first().uppercaseChar().toString(),
                                    color = Color(0xFF3498DB),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(contact.name, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text(contact.phone, color = Color.Gray, fontSize = 12.sp)
                            }
                            IconButton(onClick = {
                                onContactsChanged(contacts.filterIndexed { i, _ -> i != index })
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color(0xFFE74C3C))
                            }
                        }
                    }
                    if (index < contacts.lastIndex) Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    @Composable
    fun HeartRateCard(heartRate: String) {
        val bpm = heartRate.substringBefore(" BPM").toIntOrNull() ?: 0
        val hrColor = when {
            bpm == 0      -> Color(0xFF5A6272)
            bpm < 50 || bpm > 130 -> Color(0xFFE74C3C)
            bpm < 60 || bpm > 100 -> Color(0xFFF39C12)
            else          -> Color(0xFF2ECC71)
        }
        val hrLabel = when {
            bpm == 0   -> "NO DATA"
            bpm < 50   -> "BRADYCARDIA"
            bpm > 130  -> "TACHYCARDIA"
            bpm < 60   -> "LOW NORMAL"
            bpm > 100  -> "ELEVATED"
            else       -> "NORMAL"
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(90.dp)
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(4.dp).background(hrColor, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text("HEART RATE", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(heartRate, color = hrColor, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Normal: 60\u2013100 BPM", color = Color.Gray, fontSize = 10.sp)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .background(hrColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(hrLabel, color = hrColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    @Composable
    fun SettingsScreen(logs: String, onConnect: () -> Unit) {
        val scrollState = rememberScrollState()

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Device Connectivity", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onConnect,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3498DB)),
                modifier = Modifier.fillMaxWidth().height(55.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("SCAN & CONNECT WEARABLE", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(24.dp))
            Text("System Diagnostics:", color = Color.Gray, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF161B22), RoundedCornerShape(8.dp))
                    .padding(12.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(logs, fontSize = 12.sp, color = Color(0xFF58A6FF), lineHeight = 18.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }

    @Composable
    fun MetricCard(title: String, value: String, accentColor: Color = Color(0xFF3498DB), modifier: Modifier = Modifier) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(12.dp),
            modifier = modifier.height(90.dp)
        ) {
            Column(Modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(4.dp).background(accentColor, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(title, color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(6.dp))
                Text(value, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ======================================================================
// MATHEMATICAL CLONE OF PYTHON SCIPY/NUMPY PIPELINE (Unchanged)
// ======================================================================
object SignalProcessor {

    fun cleanSignal(input: FloatArray): FloatArray {
        val cleaned = FloatArray(input.size) { i ->
            if (input[i].isNaN() || input[i].isInfinite()) 0.0f else input[i]
        }
        val detrended = linearDetrend(cleaned)
        return savgolFilter11Tap(detrended)
    }

    private fun linearDetrend(y: FloatArray): FloatArray {
        val n = y.size.toFloat()
        var sumX = 0f
        var sumY = 0f
        var sumXY = 0f
        var sumX2 = 0f

        for (i in y.indices) {
            val x = i.toFloat()
            sumX += x
            sumY += y[i]
            sumXY += x * y[i]
            sumX2 += x * x
        }

        val denominator = (n * sumX2) - (sumX * sumX)
        if (denominator == 0f) return y

        val m = ((n * sumXY) - (sumX * sumY)) / denominator
        val c = (sumY - (m * sumX)) / n

        return FloatArray(y.size) { i -> y[i] - ((m * i.toFloat()) + c) }
    }

    private fun savgolFilter11Tap(data: FloatArray): FloatArray {
        val coeffs = floatArrayOf(-36f, 9f, 44f, 69f, 84f, 89f, 84f, 69f, 44f, 9f, -36f)
        val norm = 429f
        val result = FloatArray(data.size)
        val halfWindow = 5

        for (i in data.indices) {
            var sum = 0f
            for (j in -halfWindow..halfWindow) {
                val idx = (i + j).coerceIn(0, data.size - 1)
                sum += data[idx] * coeffs[j + halfWindow]
            }
            result[i] = sum / norm
        }
        return result
    }

    fun isMechanicallySound(window: FloatArray): Boolean {
        if (window.isEmpty()) return false

        val mean = window.average().toFloat()
        var varianceSum = 0f
        var extremeCount = 0

        for (v in window) {
            varianceSum += (v - mean) * (v - mean)
            if (abs(v) > 3.0f) extremeCount++
        }

        val stdDev = Math.sqrt((varianceSum / window.size).toDouble()).toFloat()
        val extremeRatio = extremeCount.toFloat() / window.size

        if (stdDev < 0.001f) return false
        if (extremeRatio > 0.1f) return false

        return true
    }

    fun checkStructuralError(input: FloatArray, recon: FloatArray, mse: Float): Boolean {
        if (mse <= 0.30f) return true

        var errorInQrsSum = 0f
        var qrsCount = 0
        var totalErrorSum = 0f

        for (i in input.indices) {
            val diff = abs(input[i] - recon[i])
            totalErrorSum += diff

            if (abs(input[i]) > 0.4f) {
                errorInQrsSum += diff
                qrsCount++
            }
        }

        if (qrsCount > 0) {
            val errorInQrs = errorInQrsSum / qrsCount
            val errorTotal = totalErrorSum / input.size
            return errorInQrs > errorTotal
        }
        return false
    }

    /**
     * Estimates heart rate (BPM) by detecting R-peaks in the centered ECG signal.
     * Uses a local-maximum threshold approach with a 300 ms refractory period.
     * @param signal  Mean-centered ECG window (2500 samples at 250 Hz = 10 s)
     * @param sampleRate  Sampling frequency in Hz (default 250)
     * @return Heart rate in BPM, or 0 if detection failed
     */
    fun calculateHeartRate(signal: FloatArray, sampleRate: Int = 250): Int {
        if (signal.size < sampleRate) return 0

        val maxVal = signal.maxOrNull() ?: return 0
        if (maxVal <= 0f) return 0

        // Threshold: 55 % of the tallest R-peak amplitude
        val threshold = maxVal * 0.55f
        // Minimum R-R distance: 300 ms → 75 samples (caps at ~200 BPM)
        val minPeakDist = (sampleRate * 0.30f).toInt()

        var peakCount = 0
        var lastPeakIdx = -minPeakDist

        for (i in 1 until signal.size - 1) {
            if (signal[i] > threshold &&
                signal[i] >= signal[i - 1] &&
                signal[i] >= signal[i + 1] &&
                (i - lastPeakIdx) >= minPeakDist) {
                peakCount++
                lastPeakIdx = i
            }
        }

        val durationSec = signal.size.toFloat() / sampleRate
        return if (peakCount > 0) ((peakCount.toFloat() / durationSec) * 60f).toInt() else 0
    }
}