package com.example.ecgguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import androidx.compose.animation.core.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    private var streamManager: BleStreamManager? = null
    private var usbStreamManager: UsbStreamManager? = null
    @Volatile private var ecgDataCallback: ((FloatArray, String) -> Unit)? = null
    private var model: ECGModel? = null

    @Suppress("DEPRECATION")
    private fun isBackgroundMonitoringServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == BackgroundMonitoringService::class.java.name }
    }

    // App-wide dark palette (plum/brown family) for a cohesive visual language.
    private val uiBgTop = Color(0xFF23141F)
    private val uiBgMid = Color(0xFF2B1823)
    private val uiBgBottom = Color(0xFF2E1F1B)
    private val uiPanel = Color(0xFF3A2532)
    private val uiPanelAlt = Color(0xFF4A2E3D)
    private val uiStroke = Color(0xFF6A4357)
    private val uiAccent = Color(0xFFE879A8)
    private val uiAccentAlt = Color(0xFFCC936C)
    private val uiTextMuted = Color(0xFFC0AFC0)

    private fun exportRrIntervalsCsv(rrMs: FloatArray): String? {
        if (rrMs.isEmpty()) return null
        return try {
            val dir = File(getExternalFilesDir(null), "exports").apply { mkdirs() }
            val fileName = "rr_intervals_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.csv"
            val file = File(dir, fileName)

            val content = buildString {
                appendLine("index,rr_ms,beat_time_s,inst_hr_bpm")
                var elapsedMs = 0f
                rrMs.forEachIndexed { idx, rr ->
                    elapsedMs += rr
                    val hr = if (rr > 0f) 60000f / rr else 0f
                    appendLine(
                        "${idx + 1},${String.format(Locale.US, "%.2f", rr)},${String.format(Locale.US, "%.2f", elapsedMs / 1000f)},${String.format(Locale.US, "%.1f", hr)}"
                    )
                }
            }
            file.writeText(content)
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Sends a WhatsApp message to a single phone number via the self-hosted OpenWA REST API.
     * The call is made off the main thread inside a coroutine.
     *
     * Phone number is normalised to WhatsApp chatId format: digits only + "@c.us"
     * e.g. "+923001234567" → "923001234567@c.us"
     */
    private fun sendOpenWaMessage(
        serverUrl: String,
        sessionId: String,
        apiKey: String,
        phone: String,
        message: String
    ) {
        val chatId = phone.replace(Regex("[^0-9]"), "") + "@c.us"
        val endpoint = "$serverUrl/sessions/$sessionId/messages/send-text"
        val jsonBody = JSONObject().apply {
            put("chatId", chatId)
            put("text", message)
        }.toString()

        lifecycleScope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("X-API-Key", apiKey)
                connection.doOutput = true
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(jsonBody) }
                // Read response code to complete the request (ignore body)
                connection.responseCode
            } catch (_: Exception) {
                // Network failures must not interrupt the alert flow
            } finally {
                connection?.disconnect()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendWhatsAppAlerts(contacts: List<EmergencyContact>, condition: String, patientName: String) {
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

        val displayName = if (patientName.isBlank()) "Patient" else patientName
        val text = "ECGGuard ALERT: $displayName — $condition. Location: $locationText"

        // Open the SMS composer for each contact with message pre-filled.
        // This avoids needing SEND_SMS permission and lets the user confirm/send.
        contacts.forEach { contact ->
            try {
                val phone = contact.phone.replace(Regex("[^0-9+]"), "")
                val uri = Uri.parse("smsto:$phone")
                val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                    putExtra("sms_body", text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                // If SMS app isn't available or URI fails, ignore and continue
            }
        }

        // Also send via WhatsApp through OpenWA (if configured)
        val openWa = OpenWaConfig.get(this)
        if (openWa.enabled
            && openWa.serverUrl.isNotBlank()
            && openWa.sessionId.isNotBlank()
            && openWa.apiKey.isNotBlank()
        ) {
            contacts.forEach { contact ->
                sendOpenWaMessage(openWa.serverUrl, openWa.sessionId, openWa.apiKey, contact.phone, text)
            }
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
                Surface(color = uiBgTop, contentColor = Color.White) {
                    MainApp()
                }
            }
        }
    }

    @Composable
    fun MainApp() {
        // --- SHARED STATE ---
        val prefs = remember { getSharedPreferences(BackgroundMonitoringService.PREFS_NAME, Context.MODE_PRIVATE) }
        var onboardingDone by remember { mutableStateOf(prefs.getBoolean("onboarding_done", false)) }
        var patientName by remember { mutableStateOf(prefs.getString("patient_name", "") ?: "") }
        var currentScreen by remember { mutableStateOf("HOME") }
        var backgroundMonitoringEnabled by remember {
            mutableStateOf(prefs.getBoolean(BackgroundMonitoringService.KEY_BG_MONITORING_ENABLED, false))
        }

        var logs by remember { mutableStateOf("System Initialized.\nWaiting for user...") }
        // BLE-only logs and connection state (keeps model/test output out of Settings)
        var bleLogs by remember { mutableStateOf("") }
        var deviceConnected by remember { mutableStateOf(false) }
        var connectedDeviceName by remember { mutableStateOf("\u2014") }
        var lastSeen by remember { mutableStateOf("\u2014") }
        var showAdvancedLogs by remember { mutableStateOf(false) }
        var mseDisplay by remember { mutableStateOf("0.0000") }
        var latencyDisplay by remember { mutableStateOf("0 ms") }
        var heartRateDisplay by remember { mutableStateOf("-- BPM") }
        var hrTrendDisplay by remember { mutableStateOf("--") }
        var sdnnDisplay by remember { mutableStateOf("-- ms") }
        var rmssdDisplay by remember { mutableStateOf("-- ms") }
        var pnn50Display by remember { mutableStateOf("-- %") }
        var dominantFreqDisplay by remember { mutableStateOf("-- Hz") }
        var qualityDisplay by remember { mutableStateOf("--") }
        var rPeakDisplay by remember { mutableStateOf("--") }
        var qrsDisplay by remember { mutableStateOf("--") }
        var heartRateHistory by remember { mutableStateOf(IntArray(0)) }
        var rrIntervalsForExport by remember { mutableStateOf(FloatArray(0)) }
        var exportStatus by remember { mutableStateOf("") }
        var statusDisplay by remember { mutableStateOf("AWAITING CONNECTION") }
        var statusColor by remember { mutableStateOf(Color.DarkGray) }

        // State for the graph
        var graphData by remember { mutableStateOf(FloatArray(0)) }
        var isBuffering by remember { mutableStateOf(true) }

        // --- EMERGENCY / ANOMALY ALERT STATE ---
        var showAnomalyDialog by remember { mutableStateOf(false) }
        var anomalyEpisodeHandled by remember { mutableStateOf(false) }
        var countdownSeconds by remember { mutableStateOf(10) }
        // --- BRADYCARDIA ALERT STATE ---
        var showBradyDialog by remember { mutableStateOf(false) }
        var bradyEpisodeHandled by remember { mutableStateOf(false) }
        var bradyCountdown by remember { mutableStateOf(15) }
        var bradyThreshold by remember { mutableStateOf(prefs.getInt("brady_threshold", 50)) }   // BPM threshold
        var bradyAlarmVolume by remember { mutableStateOf(prefs.getInt("brady_alarm_volume", 85)) } // 0–100
        var contacts by remember { mutableStateOf(EmergencyContactStore.getContacts(this@MainActivity)) }

        // --- OPENWA STATE ---
        var openWaSettings by remember { mutableStateOf(OpenWaConfig.get(this@MainActivity)) }

        if (!onboardingDone) {
            OnboardingScreen(
                onFinish = {
                    prefs.edit().putBoolean("onboarding_done", true).apply()
                    onboardingDone = true
                }
            )
            return
        }

        // First-launch patient name prompt
        if (patientName.isBlank()) {
            PatientNameScreen(onSave = { name ->
                val cleaned = name.trim()
                prefs.edit().putString("patient_name", cleaned).apply()
                patientName = cleaned
            })
            return
        }

        LaunchedEffect(Unit) {
            val running = isBackgroundMonitoringServiceRunning()
            backgroundMonitoringEnabled = running
            prefs.edit().putBoolean(BackgroundMonitoringService.KEY_BG_MONITORING_ENABLED, running).apply()
        }

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
                    sendWhatsAppAlerts(contacts, statusDisplay, patientName)
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
                    sendWhatsAppAlerts(contacts, statusDisplay, patientName)
                }
            )
        }

            @Composable
            fun BradyAlertDialog(
                countdown: Int,
                hasContacts: Boolean,
                volume: Int,
                onCancel: () -> Unit,
                onAlert: () -> Unit
            ) {
                val context = LocalContext.current
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                val toneGenerator = remember(volume) { ToneGenerator(AudioManager.STREAM_ALARM, volume.coerceIn(0, 100)) }

                DisposableEffect(Unit) {
                    onDispose {
                        try {
                            toneGenerator.release()
                        } catch (_: Exception) { }
                    }
                }

                LaunchedEffect(countdown) {
                    try {
                        toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500)
                        if (vibrator != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
                            } else {
                                @Suppress("DEPRECATION")
                                vibrator.vibrate(400)
                            }
                        }
                    } catch (_: Exception) { /* ignore vibration failures */ }
                }

                AlertDialog(
                    onDismissRequest = { /* Require explicit choice */ },
                    containerColor = uiPanel,
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = uiAccentAlt, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("LOW HEART RATE", color = uiAccentAlt, fontSize = 15.sp, fontWeight = FontWeight.Black)
                        }
                    },
                    text = {
                        Column {
                            Text(
                                "A sustained low heart rate (bradycardia) was detected. If you feel dizzy, faint, or unresponsive, notify emergency contacts now.",
                                color = Color.White,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                            Spacer(Modifier.height(12.dp))
                            if (!hasContacts) {
                                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF4A2D26)), shape = RoundedCornerShape(8.dp)) {
                                    Text(
                                        "⚠ No emergency contacts saved. Add contacts using the person icon in the top bar.",
                                        color = uiAccentAlt,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            } else {
                                Text("Tap I'M OK to dismiss, or SEND ALERT to notify contacts immediately.", color = uiTextMuted, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(progress = { countdown / 15f }, modifier = Modifier.fillMaxWidth(), color = uiAccentAlt, trackColor = uiStroke)
                            Spacer(Modifier.height(6.dp))
                            Text("Auto-sending in $countdown s…", color = uiTextMuted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        }
                    },
                    confirmButton = {
                        Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6AA57B)), shape = RoundedCornerShape(8.dp)) {
                            Text("I'M OK", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        Button(onClick = onAlert, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB96A7D)), shape = RoundedCornerShape(8.dp)) {
                            Text("SEND ALERT", fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }

        // Countdown & auto-send for bradycardia alerts
        LaunchedEffect(showBradyDialog) {
            if (showBradyDialog) {
                bradyCountdown = 15
                while (showBradyDialog && bradyCountdown > 0) {
                    delay(1000L)
                    if (showBradyDialog) bradyCountdown--
                }
                if (showBradyDialog) {
                    showBradyDialog = false
                    sendWhatsAppAlerts(contacts, "BRADYCARDIA", patientName)
                }
            }
        }

        if (showBradyDialog) {
            BradyAlertDialog(
                countdown = bradyCountdown,
                hasContacts = contacts.isNotEmpty(),
                volume = bradyAlarmVolume,
                onCancel = { showBradyDialog = false },
                onAlert = {
                    showBradyDialog = false
                    sendWhatsAppAlerts(contacts, "BRADYCARDIA", patientName)
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

        // Shared ECG data-processing callback – assigned here so both BLE and USB
        // stream managers run the same pipeline without duplicating code.
        ecgDataCallback = ecgCb@ { inputData: FloatArray, _: String ->

            val tStart = System.nanoTime()

            val mvData = FloatArray(inputData.size) { i -> inputData[i] * 0.001f }
            val cleanData = SignalProcessor.cleanSignal(mvData)

            if (!SignalProcessor.isMechanicallySound(cleanData)) {
                runOnUiThread {
                    statusDisplay = "ARTIFACT / MOTION DETECTED"
                    statusColor = Color.DarkGray
                    logs += "\n[Warning] Signal dropped by Gatekeeper."
                }
                return@ecgCb
            }

            val mu = cleanData.average().toFloat()
            val centeredData = FloatArray(cleanData.size) { i -> cleanData[i] - mu }

            var finalMse = 0f

            if (model != null) {
                try {
                    val (rawMse, _) = model!!.runInference(centeredData)
                    finalMse = rawMse
                } catch (e: Exception) {
                    logs += "\nModel Error: ${e.message}"
                }
            }

            val tEnd = System.nanoTime()
            val inferenceTimeMs = (tEnd - tStart) / 1_000_000.0

            val peakIndices = SignalProcessor.detectRPeakIndices(centeredData)
            val rrIntervalsMs = SignalProcessor.rrIntervalsMsFromPeaks(peakIndices)
            val hrv = SignalProcessor.calculateHrvMetrics(rrIntervalsMs)
            val beatMorphology = SignalProcessor.estimateBeatMorphology(centeredData, peakIndices)
            val signalQuality = SignalProcessor.estimateSignalQuality(cleanData)
            val dominantFreq = SignalProcessor.estimateDominantFrequencyHz(centeredData)
            val heartRate = SignalProcessor.calculateHeartRate(centeredData)

            // --- UPDATE UI STATE ---
            runOnUiThread {
                isBuffering = false
                mseDisplay = String.format("%.4f", finalMse)
                latencyDisplay = String.format("%.1f ms", inferenceTimeMs)
                heartRateDisplay = if (heartRate in 30..220) "$heartRate BPM" else "-- BPM"
                sdnnDisplay = hrv?.let { String.format("%.1f ms", it.sdnnMs) } ?: "-- ms"
                rmssdDisplay = hrv?.let { String.format("%.1f ms", it.rmssdMs) } ?: "-- ms"
                pnn50Display = hrv?.let { String.format("%.0f %%", it.pnn50) } ?: "-- %"
                dominantFreqDisplay = dominantFreq?.let { String.format("%.2f Hz", it) } ?: "-- Hz"
                qualityDisplay = "$signalQuality%"
                rPeakDisplay = beatMorphology?.let { String.format("%.2f mV", it.avgRPeakMv) } ?: "--"
                qrsDisplay = beatMorphology?.let { String.format("%.0f ms", it.avgQrsMs) } ?: "--"
                rrIntervalsForExport = rrIntervalsMs

                if (heartRate in 30..220) {
                    heartRateHistory = (heartRateHistory + heartRate).takeLast(36).toIntArray()
                }
                if (heartRateHistory.isNotEmpty()) {
                    val avg = heartRateHistory.average().toInt()
                    val minHr = heartRateHistory.minOrNull() ?: avg
                    val maxHr = heartRateHistory.maxOrNull() ?: avg
                    hrTrendDisplay = "$avg avg ($minHr-$maxHr)"
                }

                logs += "\n[Prediction] MSE: $mseDisplay | HR: $heartRateDisplay"

                // BRADYCARDIA: prompt an alarm dialog when sustained low HR is detected
                try {
                    if (heartRate > 0 && heartRate < bradyThreshold && statusDisplay != "ANOMALY DETECTED" && !bradyEpisodeHandled) {
                        showBradyDialog = true
                        bradyEpisodeHandled = true
                    }
                    if (heartRate >= bradyThreshold) {
                        bradyEpisodeHandled = false
                    }
                } catch (_: Exception) { /* non-critical guard */ }

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

        // --- BACKGROUND MANAGER ---
        if (streamManager == null) {
            streamManager = BleStreamManager(
                context = this,
                onLog = { msg ->
                    runOnUiThread {
                        // Keep BLE-related messages separate from model/test logs
                        bleLogs += "\n$msg"

                        // Heuristically update connection state / device name
                        try {
                            if (msg.contains("Connected")) {
                                deviceConnected = true
                                lastSeen = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                                if (msg.contains("TARGET FOUND")) {
                                    val start = msg.indexOf('(')
                                    val end = msg.indexOf(')')
                                    if (start >= 0 && end > start) connectedDeviceName = msg.substring(start + 1, end)
                                }
                            } else if (msg.contains("Disconnected") || msg.contains("Scan timeout") || msg.contains("Bluetooth disabled") || msg.contains("Connection error")) {
                                deviceConnected = false
                                lastSeen = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                            } else if (msg.contains("TARGET FOUND") && connectedDeviceName == "\u2014") {
                                val start = msg.indexOf('(')
                                val end = msg.indexOf(')')
                                if (start >= 0 && end > start) {
                                    connectedDeviceName = msg.substring(start + 1, end)
                                    lastSeen = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                                }
                            }
                        } catch (_: Exception) { /* non-critical */ }
                    }
                },
                onDataReceived = { data, _ -> ecgDataCallback?.invoke(data, "") }
            )
        }

        // --- NAVIGATION ROUTING ---
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(uiBgTop, uiBgMid, uiBgBottom)
                    )
                )
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(uiPanel)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("ECGGuard", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Real-time ECG Intelligence", fontSize = 11.sp, color = uiTextMuted)
                }
                if (backgroundMonitoringEnabled) {
                    Text(
                        "BG ON",
                        color = Color(0xFF9FE0A3),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Color(0x339FE0A3), RoundedCornerShape(7.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (currentScreen) {
                    "HOME" -> HomeScreen(
                        statusDisplay = statusDisplay,
                        statusColor = statusColor,
                        mseDisplay = mseDisplay,
                        latencyDisplay = latencyDisplay,
                        heartRateDisplay = heartRateDisplay,
                        sdnnDisplay = sdnnDisplay,
                        rmssdDisplay = rmssdDisplay,
                        pnn50Display = pnn50Display,
                        dominantFreqDisplay = dominantFreqDisplay,
                        qualityDisplay = qualityDisplay,
                        hrTrendDisplay = hrTrendDisplay,
                        rPeakDisplay = rPeakDisplay,
                        qrsDisplay = qrsDisplay,
                        graphData = graphData,
                        isBuffering = isBuffering
                    )
                    "SETTINGS" -> SettingsScreen(
                        connected = deviceConnected,
                        deviceName = connectedDeviceName,
                        lastSeen = lastSeen,
                        patientName = patientName,
                        onPatientNameChange = { newName ->
                            val cleaned = newName.trim()
                            prefs.edit().putString("patient_name", cleaned).apply()
                            patientName = cleaned
                        },
                        backgroundMonitoringEnabled = backgroundMonitoringEnabled,
                        onStartBackgroundMonitoring = {
                            try {
                                streamManager?.disconnect()
                                streamManager = null
                                val svcIntent = Intent(this@MainActivity, BackgroundMonitoringService::class.java).apply {
                                    action = BackgroundMonitoringService.ACTION_START
                                }
                                ContextCompat.startForegroundService(this@MainActivity, svcIntent)
                                backgroundMonitoringEnabled = true
                                prefs.edit().putBoolean(BackgroundMonitoringService.KEY_BG_MONITORING_ENABLED, true).apply()
                                exportStatus = "Background monitoring enabled."
                            } catch (e: Exception) {
                                backgroundMonitoringEnabled = false
                                prefs.edit().putBoolean(BackgroundMonitoringService.KEY_BG_MONITORING_ENABLED, false).apply()
                                exportStatus = "Failed to enable background monitoring: ${e.message ?: "unknown error"}"
                                Log.e("ECGGuard", "Failed to start background monitoring", e)
                            }
                        },
                        onStopBackgroundMonitoring = {
                            try {
                                // Stop directly to avoid background-start restrictions while stopping.
                                stopService(Intent(this@MainActivity, BackgroundMonitoringService::class.java))
                                backgroundMonitoringEnabled = false
                                prefs.edit().putBoolean(BackgroundMonitoringService.KEY_BG_MONITORING_ENABLED, false).apply()
                                exportStatus = "Background monitoring disabled."
                            } catch (e: Exception) {
                                exportStatus = "Failed to disable background monitoring: ${e.message ?: "unknown error"}"
                                Log.e("ECGGuard", "Failed to stop background monitoring", e)
                            }
                        },
                        bleLogs = bleLogs,
                        showAdvanced = showAdvancedLogs,
                        onToggleAdvanced = { showAdvancedLogs = !showAdvancedLogs },
                        exportStatus = exportStatus,
                        canExportRr = rrIntervalsForExport.isNotEmpty(),
                        onExportRr = {
                            val path = exportRrIntervalsCsv(rrIntervalsForExport)
                            exportStatus = if (path != null) "Exported RR CSV: $path" else "Export failed (no RR data yet)."
                        },
                        bradyThreshold = bradyThreshold,
                        onBradyThresholdChange = {
                            bradyThreshold = it
                            prefs.edit().putInt("brady_threshold", it).apply()
                        },
                        bradyAlarmVolume = bradyAlarmVolume,
                        onBradyVolumeChange = {
                            bradyAlarmVolume = it
                            prefs.edit().putInt("brady_alarm_volume", it).apply()
                        },
                        onConnect = { permissionLauncher.launch(permissionsToRequest) },
                        onConnectUsb = {
                            streamManager?.disconnect()
                            streamManager = null
                            usbStreamManager?.disconnect()
                            usbStreamManager = UsbStreamManager(
                                onLog = { msg ->
                                    runOnUiThread {
                                        bleLogs += "\n[USB] $msg"
                                        when {
                                            msg.contains("USB client connected") -> {
                                                deviceConnected = true
                                                connectedDeviceName = "USB Debug (PC)"
                                                lastSeen = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                                            }
                                            msg.contains("disconnected", ignoreCase = true) ||
                                            msg.contains("error", ignoreCase = true) -> {
                                                deviceConnected = false
                                                lastSeen = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                                            }
                                        }
                                    }
                                },
                                onDataReceived = { data, _ -> ecgDataCallback?.invoke(data, "") }
                            )
                            usbStreamManager!!.connect()
                            statusDisplay = "USB: Waiting for adb forward…"
                            statusColor = Color(0xFF5B9BD5)
                            isBuffering = true
                        },
                        openWaSettings = openWaSettings,
                        onOpenWaChange = { updated ->
                            OpenWaConfig.save(this@MainActivity, updated)
                            openWaSettings = updated
                        }
                    )
                    "CONTACTS" -> EmergencyContactsScreen(
                        contacts = contacts,
                        onContactsChanged = { updated ->
                            contacts = updated
                            EmergencyContactStore.saveContacts(this@MainActivity, updated)
                        }
                    )
                }
            }

            NavigationBar(
                containerColor = uiPanel,
                tonalElevation = 6.dp
            ) {
                NavigationBarItem(
                    selected = currentScreen == "HOME",
                    onClick = { currentScreen = "HOME" },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = uiAccent,
                        selectedTextColor = uiAccent,
                        indicatorColor = Color(0x33E879A8),
                        unselectedIconColor = uiTextMuted,
                        unselectedTextColor = uiTextMuted
                    ),
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = currentScreen == "CONTACTS",
                    onClick = { currentScreen = "CONTACTS" },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = uiAccent,
                        selectedTextColor = uiAccent,
                        indicatorColor = Color(0x33E879A8),
                        unselectedIconColor = uiTextMuted,
                        unselectedTextColor = uiTextMuted
                    ),
                    icon = { Icon(Icons.Default.Person, contentDescription = "Contacts") },
                    label = { Text("Contacts") }
                )
                NavigationBarItem(
                    selected = currentScreen == "SETTINGS",
                    onClick = { currentScreen = "SETTINGS" },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = uiAccent,
                        selectedTextColor = uiAccent,
                        indicatorColor = Color(0x33E879A8),
                        unselectedIconColor = uiTextMuted,
                        unselectedTextColor = uiTextMuted
                    ),
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
            }
        }
    }

    @Composable
    fun OnboardingScreen(onFinish: () -> Unit) {
        val pages = listOf(
            Triple("Welcome to ECGGuard", "Continuous ECG monitoring with on-device intelligence.", "Track rhythm, HRV, and signal quality in real time."),
            Triple("Safety First", "Emergency contacts can be alerted when serious events occur.", "Set brady alarm threshold and alarm volume in Settings."),
            Triple("Background Monitoring", "Use foreground monitoring service to keep tracking with screen off.", "Start/Stop background mode from Settings > Background Monitoring.")
        )
        var page by remember { mutableStateOf(0) }
        val isLast = page == pages.lastIndex

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(uiBgTop, uiBgMid, uiBgBottom)))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("ECGGuard Setup", color = uiAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(18.dp))
                Text(pages[page].first, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(12.dp))
                Text(pages[page].second, color = Color(0xFFE0D0DE), fontSize = 15.sp, lineHeight = 22.sp)
                Spacer(Modifier.height(10.dp))
                Text(pages[page].third, color = uiTextMuted, fontSize = 13.sp, lineHeight = 20.sp)
            }

            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    repeat(pages.size) { idx ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .background(
                                    if (idx == page) uiAccent else uiStroke.copy(alpha = 0.45f),
                                    RoundedCornerShape(3.dp)
                                )
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onFinish,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(9.dp)
                    ) {
                        Text("SKIP")
                    }
                    Button(
                        onClick = {
                            if (isLast) onFinish() else page++
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = uiAccent),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(9.dp)
                    ) {
                        Text(if (isLast) "GET STARTED" else "NEXT", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    @Composable
    fun PatientNameScreen(onSave: (String) -> Unit) {
        var name by remember { mutableStateOf("") }
        val nameFieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = uiAccent,
            unfocusedBorderColor = uiStroke,
            focusedLabelColor = Color.White,
            unfocusedLabelColor = Color.White,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = uiAccent,
            focusedPlaceholderColor = Color.White.copy(alpha = 0.7f),
            unfocusedPlaceholderColor = Color.White.copy(alpha = 0.7f)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(uiBgTop, uiBgMid, uiBgBottom)))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Patient Name", color = uiAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            Text("Please enter the patient's name to use in emergency alerts.", color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Full name", color = Color.White.copy(alpha = 0.7f)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = nameFieldColors
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = { if (name.isNotBlank()) onSave(name) }, colors = ButtonDefaults.buttonColors(containerColor = uiAccent)) {
                    Text("SAVE")
                }
            }
        }
    }

    @Composable
    fun HomeScreen(
        statusDisplay: String, statusColor: Color,
        mseDisplay: String, latencyDisplay: String,
        heartRateDisplay: String,
        sdnnDisplay: String,
        rmssdDisplay: String,
        pnn50Display: String,
        dominantFreqDisplay: String,
        qualityDisplay: String,
        hrTrendDisplay: String,
        rPeakDisplay: String,
        qrsDisplay: String,
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
            Text(
                "Live Overview",
                modifier = Modifier.fillMaxWidth(),
                color = uiTextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))

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
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1E24)),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    modifier = Modifier.fillMaxWidth().height(230.dp)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (isBuffering) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    color = uiAccent,
                                    strokeWidth = 3.dp
                                )
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    "Filling 10-Second Buffer…",
                                    color = uiAccent.copy(alpha = 0.8f),
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
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF9A3D62)),
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
                    accentColor = uiAccent,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "AI Latency",
                    value = latencyDisplay,
                    accentColor = Color(0xFFB084CC),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    title = "HRV SDNN",
                    value = sdnnDisplay,
                    accentColor = Color(0xFF7EC6AB),
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "HRV RMSSD",
                    value = rmssdDisplay,
                    accentColor = uiAccentAlt,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    title = "pNN50",
                    value = pnn50Display,
                    accentColor = Color(0xFF76AF98),
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "Dominant Freq",
                    value = dominantFreqDisplay,
                    accentColor = Color(0xFFC08BA8),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    title = "Signal Quality",
                    value = qualityDisplay,
                    accentColor = Color(0xFF8DCB9B),
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "HR Trend",
                    value = hrTrendDisplay,
                    accentColor = Color(0xFFA67CB8),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    title = "R / QRS",
                    value = "$rPeakDisplay | $qrsDisplay",
                    accentColor = uiAccentAlt,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "Status",
                    value = statusDisplay.take(12),
                    accentColor = statusColor,
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
            val leftPad = 18f
            val rightPad = 12f
            val topPad = 12f
            val bottomPad = 16f
            val chartWidth = width - leftPad - rightPad
            val chartHeight = height - topPad - bottomPad
            val baselineY = topPad + chartHeight / 2f

            drawRoundRect(
                color = Color(0xFF241821),
                topLeft = Offset.Zero,
                size = androidx.compose.ui.geometry.Size(width, height),
                cornerRadius = CornerRadius(18f, 18f)
            )

            val gridMajor = Color(0xFF523443)
            val gridMinor = Color(0xFF34212B)
            repeat(4) { i ->
                val y = topPad + (chartHeight / 4f) * i
                drawLine(if (i == 2) gridMajor else gridMinor, Offset(leftPad, y), Offset(leftPad + chartWidth, y), strokeWidth = 0.8f)
            }
            repeat(5) { i ->
                val x = leftPad + (chartWidth / 4f) * i
                drawLine(if (i == 2) gridMajor else gridMinor, Offset(x, topPad), Offset(x, topPad + chartHeight), strokeWidth = 0.8f)
            }

            drawLine(Color(0xFF8A6678), Offset(leftPad, topPad), Offset(leftPad, topPad + chartHeight), strokeWidth = 1.4f)
            drawLine(Color(0xFF8A6678), Offset(leftPad, baselineY), Offset(leftPad + chartWidth, baselineY), strokeWidth = 1.1f)

            val maxAbs = maxOf(data.maxOrNull()?.let { abs(it) } ?: 0f, data.minOrNull()?.let { abs(it) } ?: 0f).coerceAtLeast(1f)
            val amplitude = chartHeight * 0.38f
            val stepX = chartWidth / (data.size - 1).coerceAtLeast(1)

            val linePath = Path()
            val fillPath = Path()

            data.forEachIndexed { index, value ->
                val normalized = (value / maxAbs).coerceIn(-1f, 1f)
                val x = leftPad + index * stepX
                val y = baselineY - normalized * amplitude

                if (index == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, baselineY)
                    fillPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo(leftPad + chartWidth, baselineY)
            fillPath.close()

            drawPath(fillPath, uiAccent.copy(alpha = 0.08f))
            drawPath(linePath, uiAccent.copy(alpha = 0.18f), style = Stroke(width = 10f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(linePath, uiAccent.copy(alpha = 0.45f), style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(linePath, uiAccent, style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))

            val markerStep = (data.size / 10).coerceAtLeast(1)
            for (index in data.indices step markerStep) {
                val normalized = (data[index] / maxAbs).coerceIn(-1f, 1f)
                val x = leftPad + index * stepX
                val y = baselineY - normalized * amplitude
                drawCircle(uiAccentAlt, radius = 2.2f, center = Offset(x, y))
            }
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
            containerColor = uiPanel,
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
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF4A2D26)),
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
                            color = uiTextMuted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { countdown / 10f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFFE74C3C),
                        trackColor = Color(0xFF4A2F3B)
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "Auto-sending in $countdown s…",
                        color = uiTextMuted,
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
        val scrollState = rememberScrollState()
        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = uiAccent,
            unfocusedBorderColor = uiStroke,
            focusedLabelColor = uiAccent,
            unfocusedLabelColor = uiTextMuted,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = uiAccent
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
                color = uiTextMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(20.dp))

            // ── Add contact form ──────────────────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(containerColor = uiPanel),
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
                        label = { Text("WhatsApp Number") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        colors = fieldColors,
                        supportingText = {
                            Text(
                                "International format, e.g. +923001234567",
                                color = Color.White,
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
                            if (name.isNotEmpty() && phone.length >= 7) {
                                onContactsChanged(contacts + EmergencyContact(name, phone))
                                nameInput = ""
                                phoneInput = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = uiAccent),
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
                        .background(uiPanel, RoundedCornerShape(12.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No contacts saved yet.", color = Color.White, fontSize = 14.sp)
                }
            } else {
                Text(
                    "${contacts.size} contact${if (contacts.size > 1) "s" else ""} saved",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                contacts.forEachIndexed { index, contact ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = uiPanel),
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
                                    .background(uiPanelAlt, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    contact.name.first().uppercaseChar().toString(),
                                    color = uiAccent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(contact.name, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text(contact.phone, color = Color.White, fontSize = 12.sp)
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

        val pulseDuration = if (bpm in 30..180) ((60_000f / bpm).toInt()).coerceIn(300, 1600) else 900
        val transition = rememberInfiniteTransition(label = "heartPulse")
        val heartScale by transition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(pulseDuration, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "heartScale"
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = uiPanel),
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
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = hrColor.copy(alpha = 0.85f),
                            modifier = Modifier
                                .size((14f * heartScale).dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("HEART RATE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(heartRate, color = hrColor, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Normal: 60\u2013100 BPM", color = Color.White, fontSize = 10.sp)
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
    fun SettingsScreen(
        connected: Boolean,
        deviceName: String,
        lastSeen: String,
        patientName: String,
        onPatientNameChange: (String) -> Unit,
        backgroundMonitoringEnabled: Boolean,
        onStartBackgroundMonitoring: () -> Unit,
        onStopBackgroundMonitoring: () -> Unit,
        bleLogs: String,
        showAdvanced: Boolean,
        onToggleAdvanced: () -> Unit,
        exportStatus: String,
        canExportRr: Boolean,
        onExportRr: () -> Unit,
        bradyThreshold: Int,
        onBradyThresholdChange: (Int) -> Unit,
        bradyAlarmVolume: Int,
        onBradyVolumeChange: (Int) -> Unit,
        onConnect: () -> Unit,
        onConnectUsb: () -> Unit,
        openWaSettings: OpenWaSettings,
        onOpenWaChange: (OpenWaSettings) -> Unit
    ) {
        val scrollState = rememberScrollState()
        val patientFieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = uiAccent,
            unfocusedBorderColor = uiStroke,
            focusedLabelColor = Color.White,
            unfocusedLabelColor = Color.White,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = uiAccent
        )

        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp)) {
            Spacer(Modifier.height(18.dp))
            // Patient identity card
            Card(
                colors = CardDefaults.cardColors(containerColor = uiPanel),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
            ) {
                var showEdit by remember { mutableStateOf(false) }
                var tempName by remember { mutableStateOf(patientName) }
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Patient", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(if (patientName.isBlank()) "(not set)" else patientName, color = Color.White, fontSize = 13.sp)
                        }
                        IconButton(onClick = { tempName = patientName; showEdit = true }) {
                            Icon(Icons.Default.Person, contentDescription = "Edit patient name", tint = uiAccent)
                        }
                    }
                }
                if (showEdit) {
                    AlertDialog(
                        onDismissRequest = { showEdit = false },
                        containerColor = uiPanel,
                        title = { Text("Edit patient name", color = Color.White) },
                        text = {
                            OutlinedTextField(
                                value = tempName,
                                onValueChange = { tempName = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = patientFieldColors,
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White)
                            )
                        },
                        confirmButton = {
                            Button(onClick = { onPatientNameChange(tempName); showEdit = false }, colors = ButtonDefaults.buttonColors(containerColor = uiAccent)) { Text("Save") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showEdit = false }) { Text("Cancel") }
                        }
                    )
                }
            }

            Text("Device Connectivity", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text("Configure alerts, diagnostics, and background mode.", color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = uiPanel),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = if (connected) Color(0xFF9FE0A3) else Color(0xFF7C6674)
                    Box(Modifier.size(14.dp).background(statusColor, CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (connected) "Connected" else "Disconnected", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(deviceName, color = Color.White, fontSize = 12.sp)
                        Text("Last seen: $lastSeen", color = Color.White, fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onConnect, colors = ButtonDefaults.buttonColors(containerColor = uiAccent), shape = RoundedCornerShape(8.dp)) {
                        Text("SCAN & CONNECT", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── USB DEBUG MODE ─────────────────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF22203A)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(uiAccentAlt, RoundedCornerShape(3.dp))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("USB Debug Mode (ADB)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Stream ECG directly from your PC via USB without an ESP32 or BLE.",
                        color = uiTextMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "adb forward tcp:9999 tcp:9999\npython ecg_replay.py --usb --file ecg.csv",
                        color = uiAccentAlt,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onConnectUsb,
                        colors = ButtonDefaults.buttonColors(containerColor = uiAccentAlt),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("START USB LISTENER", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = uiPanel),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text("Background Monitoring", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                        Text(
                            if (backgroundMonitoringEnabled) "Running in foreground service. You can lock screen and keep monitoring."
                            else "Start service to keep BLE monitoring active when the app is in background.",
                            color = Color.White,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = onStartBackgroundMonitoring,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6AA57B)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("START BG", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onStopBackgroundMonitoring,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB96A7D)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("STOP BG", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── BRADYCARDIA ALARM SETTINGS ─────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(containerColor = uiPanel),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text("Bradycardia Alarm", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(14.dp))

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Threshold", color = Color.White, fontSize = 11.sp)
                            Text("$bradyThreshold BPM", color = uiAccentAlt, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(8.dp))
                        Row {
                            IconButton(onClick = { if (bradyThreshold > 30) onBradyThresholdChange(bradyThreshold - 5) }) {
                                Text("−", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            }
                            IconButton(onClick = { if (bradyThreshold < 90) onBradyThresholdChange(bradyThreshold + 5) }) {
                                Text("+", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Slider(
                        value = bradyThreshold.toFloat(),
                        onValueChange = { onBradyThresholdChange(it.toInt()) },
                        valueRange = 30f..90f,
                        steps = 11,
                        colors = SliderDefaults.colors(thumbColor = uiAccentAlt, activeTrackColor = uiAccentAlt)
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Alarm Volume", color = Color.White, fontSize = 11.sp)
                            Text("$bradyAlarmVolume %", color = uiAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Slider(
                        value = bradyAlarmVolume.toFloat(),
                        onValueChange = { onBradyVolumeChange(it.toInt()) },
                        valueRange = 0f..100f,
                        steps = 19,
                        colors = SliderDefaults.colors(thumbColor = uiAccent, activeTrackColor = uiAccent)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── OPENWA WHATSAPP INTEGRATION ────────────────────────────────
            var openWaExpanded by remember { mutableStateOf(false) }
            var tempUrl     by remember { mutableStateOf(openWaSettings.serverUrl) }
            var tempSession by remember { mutableStateOf(openWaSettings.sessionId) }
            var tempKey     by remember { mutableStateOf(openWaSettings.apiKey) }
            var showApiKey  by remember { mutableStateOf(false) }
            val openWaFieldColors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = uiAccent,
                unfocusedBorderColor = uiStroke,
                focusedLabelColor    = Color.White,
                unfocusedLabelColor  = uiTextMuted,
                focusedTextColor     = Color.White,
                unfocusedTextColor   = Color.White,
                cursorColor          = uiAccent
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = uiPanel),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("WhatsApp Alerts (OpenWA)", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (openWaSettings.enabled) "Enabled — alerts sent via self-hosted OpenWA"
                                else "Disabled — configure OpenWA server to enable",
                                color = if (openWaSettings.enabled) Color(0xFF9FE0A3) else uiTextMuted,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                        Switch(
                            checked = openWaSettings.enabled,
                            onCheckedChange = {
                                onOpenWaChange(openWaSettings.copy(enabled = it))
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = uiAccent)
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { openWaExpanded = !openWaExpanded },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            if (openWaExpanded) "Hide configuration ▲" else "Configure server ▼",
                            color = uiAccent,
                            fontSize = 12.sp
                        )
                    }

                    if (openWaExpanded) {
                        Spacer(Modifier.height(10.dp))

                        OutlinedTextField(
                            value = tempUrl,
                            onValueChange = { tempUrl = it },
                            label = { Text("Server URL") },
                            placeholder = { Text("http://192.168.1.100:3000", color = uiTextMuted, fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = openWaFieldColors,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                        )
                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = tempSession,
                            onValueChange = { tempSession = it },
                            label = { Text("Session ID") },
                            placeholder = { Text("my-session", color = uiTextMuted, fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = openWaFieldColors
                        )
                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = tempKey,
                            onValueChange = { tempKey = it },
                            label = { Text("API Key (Operator)") },
                            singleLine = true,
                            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = openWaFieldColors,
                            trailingIcon = {
                                TextButton(onClick = { showApiKey = !showApiKey }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Text(if (showApiKey) "Hide" else "Show", color = uiAccent, fontSize = 11.sp)
                                }
                            }
                        )
                        Spacer(Modifier.height(12.dp))

                        Button(
                            onClick = {
                                onOpenWaChange(
                                    openWaSettings.copy(
                                        serverUrl = tempUrl.trimEnd('/'),
                                        sessionId = tempSession.trim(),
                                        apiKey    = tempKey.trim()
                                    )
                                )
                                openWaExpanded = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = uiAccent),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("SAVE", fontWeight = FontWeight.Bold)
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            "How it works: run OpenWA server locally or in Docker, create a session by scanning the QR code in the dashboard, generate an operator API key, then paste the values above. On each alert, ECGGuard will POST to /sessions/{sessionId}/messages/send-text for every emergency contact (phone normalised to country code + digits + @c.us).",
                            color = uiTextMuted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Advanced diagnostics", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(checked = showAdvanced, onCheckedChange = { onToggleAdvanced() }, colors = SwitchDefaults.colors(checkedThumbColor = uiAccent))
            }

            if (showAdvanced) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onExportRr,
                    enabled = canExportRr,
                    colors = ButtonDefaults.buttonColors(containerColor = uiAccent),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    Text("EXPORT RR CSV", fontWeight = FontWeight.Bold)
                }
                if (exportStatus.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(exportStatus, color = Color.White, fontSize = 11.sp, lineHeight = 16.sp)
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(uiPanelAlt, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                        .verticalScroll(scrollState)
                ) {
                    Text(if (bleLogs.isBlank()) "No BLE logs yet." else bleLogs, fontSize = 12.sp, color = uiAccent, lineHeight = 18.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            } else {
                Spacer(Modifier.height(12.dp))
                Text("Tip: Toggle to view low-level BLE logs.", color = Color.White, fontSize = 12.sp)
            }
        }
    }

    @Composable
    fun MetricCard(title: String, value: String, accentColor: Color = Color(0xFFE879A8), modifier: Modifier = Modifier) {
        Card(
            colors = CardDefaults.cardColors(containerColor = uiPanelAlt),
            shape = RoundedCornerShape(12.dp),
            modifier = modifier.height(90.dp)
        ) {
            Column(Modifier.padding(14.dp).fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(5.dp).background(accentColor, CircleShape))
                    Text(title, color = Color(0xFF95A2B3), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(8.dp))
                Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

// ======================================================================
// MATHEMATICAL CLONE OF PYTHON SCIPY/NUMPY PIPELINE (Unchanged)
// ======================================================================
object SignalProcessor {

    data class HrvMetrics(
        val sdnnMs: Float,
        val rmssdMs: Float,
        val pnn50: Float
    )

    data class BeatMorphology(
        val avgRPeakMv: Float,
        val avgQrsMs: Float
    )

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

    fun detectRPeakIndices(signal: FloatArray, sampleRate: Int = 250): IntArray {
        if (signal.size < sampleRate) return IntArray(0)

        val maxVal = signal.maxOrNull() ?: return IntArray(0)
        if (maxVal <= 0f) return IntArray(0)

        val threshold = maxVal * 0.55f
        val minPeakDist = (sampleRate * 0.30f).toInt()

        val peaks = ArrayList<Int>()
        var lastPeakIdx = -minPeakDist

        for (i in 1 until signal.size - 1) {
            if (signal[i] > threshold &&
                signal[i] >= signal[i - 1] &&
                signal[i] >= signal[i + 1] &&
                (i - lastPeakIdx) >= minPeakDist
            ) {
                peaks.add(i)
                lastPeakIdx = i
            }
        }

        return peaks.toIntArray()
    }

    fun rrIntervalsMsFromPeaks(peaks: IntArray, sampleRate: Int = 250): FloatArray {
        if (peaks.size < 2) return FloatArray(0)
        val rr = FloatArray(peaks.size - 1)
        for (i in 1 until peaks.size) {
            val samples = peaks[i] - peaks[i - 1]
            rr[i - 1] = (samples * 1000f) / sampleRate
        }
        return rr
    }

    fun calculateHrvMetrics(rrMs: FloatArray): HrvMetrics? {
        if (rrMs.size < 2) return null

        val mean = rrMs.average().toFloat()
        if (mean <= 0f) return null

        var sumSq = 0f
        rrMs.forEach { v ->
            val d = v - mean
            sumSq += d * d
        }
        val sdnn = sqrt(sumSq / rrMs.size)

        var diffSq = 0f
        var nn50 = 0
        for (i in 1 until rrMs.size) {
            val diff = rrMs[i] - rrMs[i - 1]
            val ad = abs(diff)
            diffSq += diff * diff
            if (ad > 50f) nn50++
        }
        val rmssd = sqrt(diffSq / (rrMs.size - 1).coerceAtLeast(1))
        val pnn50 = (nn50.toFloat() / (rrMs.size - 1).coerceAtLeast(1)) * 100f

        return HrvMetrics(sdnnMs = sdnn, rmssdMs = rmssd, pnn50 = pnn50)
    }

    fun estimateSignalQuality(window: FloatArray): Int {
        if (window.isEmpty()) return 0

        val mean = window.average().toFloat()
        var variance = 0f
        var extremeCount = 0
        var diffSum = 0f

        for (i in window.indices) {
            val v = window[i]
            variance += (v - mean) * (v - mean)
            if (abs(v) > 3.0f) extremeCount++
            if (i > 0) diffSum += abs(v - window[i - 1])
        }

        val stdDev = sqrt(variance / window.size)
        val extremeRatio = extremeCount.toFloat() / window.size
        val roughness = diffSum / (window.size - 1).coerceAtLeast(1)

        var score = 100f
        if (stdDev < 0.0015f) {
            score -= 55f
        } else if (stdDev < 0.004f) {
            score -= 30f
        }

        score -= (extremeRatio * 300f)
        score -= (roughness * 45f)

        return score.toInt().coerceIn(0, 100)
    }

    fun estimateDominantFrequencyHz(
        signal: FloatArray,
        sampleRate: Int = 250,
        minHz: Float = 0.5f,
        maxHz: Float = 8.0f
    ): Float? {
        if (signal.size < sampleRate || minHz >= maxHz) return null

        val bins = 60
        val step = (maxHz - minHz) / bins
        var bestFreq = minHz
        var bestPower = 0.0

        for (i in 0..bins) {
            val freq = minHz + i * step
            val omega = (2.0 * PI * freq) / sampleRate
            val coeff = 2.0 * cos(omega)
            var q0 = 0.0
            var q1 = 0.0
            var q2 = 0.0

            for (x in signal) {
                q0 = coeff * q1 - q2 + x
                q2 = q1
                q1 = q0
            }

            val power = q1 * q1 + q2 * q2 - coeff * q1 * q2
            if (power > bestPower) {
                bestPower = power
                bestFreq = freq
            }
        }

        return if (bestPower > 0.0) bestFreq else null
    }

    fun estimateBeatMorphology(signal: FloatArray, peaks: IntArray, sampleRate: Int = 250): BeatMorphology? {
        if (signal.isEmpty() || peaks.isEmpty()) return null

        var ampSum = 0f
        var qrsMsSum = 0f
        var counted = 0
        val maxHalfWindow = (sampleRate * 0.12f).toInt().coerceAtLeast(1)

        for (peak in peaks) {
            if (peak <= 0 || peak >= signal.lastIndex) continue

            val peakAmp = signal[peak]
            if (peakAmp <= 0f) continue

            val halfAmp = peakAmp * 0.5f
            var left = peak
            var right = peak

            var steps = 0
            while (left > 1 && signal[left] > halfAmp && steps < maxHalfWindow) {
                left--
                steps++
            }

            steps = 0
            while (right < signal.lastIndex - 1 && signal[right] > halfAmp && steps < maxHalfWindow) {
                right++
                steps++
            }

            val widthSamples = (right - left).coerceAtLeast(1)
            val qrsMs = (widthSamples * 1000f) / sampleRate

            ampSum += peakAmp
            qrsMsSum += qrsMs
            counted++
        }

        if (counted == 0) return null
        return BeatMorphology(avgRPeakMv = ampSum / counted, avgQrsMs = qrsMsSum / counted)
    }

    /**
     * Estimates heart rate (BPM) by detecting R-peaks in the centered ECG signal.
     * Uses a local-maximum threshold approach with a 300 ms refractory period.
     * @param signal  Mean-centered ECG window (2500 samples at 250 Hz = 10 s)
     * @param sampleRate  Sampling frequency in Hz (default 250)
     * @return Heart rate in BPM, or 0 if detection failed
     */
    fun calculateHeartRate(signal: FloatArray, sampleRate: Int = 250): Int {
        val peakCount = detectRPeakIndices(signal, sampleRate).size

        val durationSec = signal.size.toFloat() / sampleRate
        return if (peakCount > 0) ((peakCount.toFloat() / durationSec) * 60f).toInt() else 0
    }
}