package com.example.ecgguard

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import androidx.compose.animation.core.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextAlign

class MainActivity : ComponentActivity() {

    private var streamManager: BleStreamManager? = null
    private var model: ECGModel? = null

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
                            finalMse = if (rawMse > 0.30f && !isStructural) 0.0f else rawMse
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
                IconButton(onClick = { currentScreen = if (currentScreen == "HOME") "SETTINGS" else "HOME" }) {
                    Icon(
                        imageVector = if (currentScreen == "HOME") Icons.Default.Settings else Icons.Default.Close,
                        contentDescription = "Menu",
                        tint = Color.White
                    )
                }
            }

            // Screen Content
            if (currentScreen == "HOME") {
                HomeScreen(statusDisplay, statusColor, mseDisplay, latencyDisplay, heartRateDisplay, graphData, isBuffering)
            } else {
                SettingsScreen(logs, { permissionLauncher.launch(permissionsToRequest) })
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