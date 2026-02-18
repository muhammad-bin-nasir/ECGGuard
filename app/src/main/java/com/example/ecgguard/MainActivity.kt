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

                    // --- UPDATE UI STATE ---
                    runOnUiThread {
                        isBuffering = false
                        mseDisplay = String.format("%.4f", finalMse)
                        latencyDisplay = String.format("%.1f ms", inferenceTimeMs)
                        logs += "\n[Prediction] MSE: $mseDisplay | Structural: $isStructural"

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
                HomeScreen(statusDisplay, statusColor, mseDisplay, latencyDisplay, graphData, isBuffering)
            } else {
                SettingsScreen(logs, { permissionLauncher.launch(permissionsToRequest) })
            }
        }
    }

    @Composable
    fun HomeScreen(
        statusDisplay: String, statusColor: Color,
        mseDisplay: String, latencyDisplay: String,
        graphData: FloatArray, isBuffering: Boolean
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {

            // 1. STATUS BANNER
            Card(
                colors = CardDefaults.cardColors(containerColor = statusColor),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(70.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(statusDisplay, fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color.White, letterSpacing = 1.sp)
                }
            }

            Spacer(Modifier.height(24.dp))

            // 2. LIVE ECG PLOT
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF000000)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(250.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (isBuffering) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFF2ECC71))
                            Spacer(Modifier.height(16.dp))
                            Text("Filling 10-Second Buffer...", color = Color.Gray)
                        }
                    } else {
                        ECGChart(graphData)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // 3. AI METRICS
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricCard("Mean Squared Error", mseDisplay, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                MetricCard("Inference Latency", latencyDisplay, modifier = Modifier.weight(1f))
            }
        }
    }

    @Composable
    fun ECGChart(data: FloatArray) {
        if (data.isEmpty()) return

        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val width = size.width
            val height = size.height

            // Draw Background Grid (Simulating ECG Paper)
            val gridPaint = Color(0xFF003300)
            for (i in 0..10) {
                val y = height * (i / 10f)
                drawLine(gridPaint, Offset(0f, y), Offset(width, y), strokeWidth = 1f)
                val x = width * (i / 10f)
                drawLine(gridPaint, Offset(x, 0f), Offset(x, height), strokeWidth = 1f)
            }

            // Scale data to fit Canvas
            val maxVal = data.maxOrNull() ?: 1f
            val minVal = data.minOrNull() ?: -1f
            val range = (maxVal - minVal).coerceAtLeast(0.1f) // Prevent div by zero

            val path = Path()
            val stepX = width / (data.size - 1)

            data.forEachIndexed { index, value ->
                val x = index * stepX
                // Normalize Y to canvas height (inverted because Y grows downwards)
                val normalizedY = (value - minVal) / range
                val y = height - (normalizedY * height)

                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            // Draw the neon green ECG line
            drawPath(path = path, color = Color(0xFF00FF00), style = Stroke(width = 3f))
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
    fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(12.dp),
            modifier = modifier.height(100.dp)
        ) {
            Column(Modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Text(title, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(value, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
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
        return true
    }
}