package com.example.ecgguard

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
                Surface(color = Color(0xFF121212), contentColor = Color.White) {
                    StreamScreen()
                }
            }
        }
    }

    @Composable
    fun StreamScreen() {
        var logs by remember { mutableStateOf("System Initialized.\nWaiting for user...") }
        var mseDisplay by remember { mutableStateOf("0.0000") }
        var latencyDisplay by remember { mutableStateOf("0 ms") }
        var statusDisplay by remember { mutableStateOf("IDLE") }
        var statusColor by remember { mutableStateOf(Color.Gray) }

        val scrollState = rememberScrollState()

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
                statusDisplay = "SCANNING"
                statusColor = Color(0xFFFF9800)
            } else {
                logs += "\nPermissions Denied."
            }
        }

        if (streamManager == null) {
            streamManager = BleStreamManager(
                context = this,
                onLog = { msg -> runOnUiThread { logs += "\n$msg" } },
                onDataReceived = { inputData, _ ->
                    runOnUiThread {
                        statusDisplay = "PROCESSING"
                        statusColor = Color(0xFF2196F3)
                    }

                    val tStart = System.nanoTime()

                    // --- 1. UNIT CONVERSION ---
                    // The structural check (> 0.4) demands clinical mV units.
                    // IMPORTANT: Adjust this multiplier based on your ESP32's ADC resolution!
                    // If your ESP32 already sends mV, keep this as 1.0f.
                    val mvData = FloatArray(inputData.size) { i -> inputData[i] * 1.0f }

                    // --- 2. CLEANING (Matching SciPy) ---
                    var cleanData = SignalProcessor.cleanSignal(mvData)

                    // --- 3. GATEKEEPER ---
                    if (!SignalProcessor.isMechanicallySound(cleanData)) {
                        runOnUiThread {
                            statusDisplay = "ARTIFACT/MOTION"
                            statusColor = Color.DarkGray
                            logs += "\n[Warning] Signal dropped by Gatekeeper (Noise/Flatline)."
                        }
                        return@BleStreamManager
                    }

                    // --- 4. FORMAT FOR MODEL (Mean Centering) ---
                    val mu = cleanData.average().toFloat()
                    val centeredData = FloatArray(cleanData.size) { i -> cleanData[i] - mu }

                    // --- 5. INFERENCE & STRUCTURAL CHECK ---
                    var finalMse = 0f
                    var isStructural = true

                    if (model != null) {
                        try {
                            // NOTE: You must update ECGModel to return a Pair<Float, FloatArray>
                            // containing both the MSE and the Reconstructed array.
                            val (rawMse, reconstructedData) = model!!.runInference(centeredData)

                            isStructural = SignalProcessor.checkStructuralError(centeredData, reconstructedData, rawMse)

                            finalMse = if (rawMse > 0.30f && !isStructural) 0.0f else rawMse

                        } catch (e: Exception) {
                            logs += "\nModel Error: ${e.message}"
                        }
                    }

                    val tEnd = System.nanoTime()
                    val inferenceTimeMs = (tEnd - tStart) / 1_000_000.0

                    // --- 6. UPDATE UI ---
                    runOnUiThread {
                        mseDisplay = String.format("%.4f", finalMse)
                        latencyDisplay = String.format("%.2f ms", inferenceTimeMs)
                        logs += "\n[Prediction] MSE: $mseDisplay | Structural: $isStructural"

                        if (finalMse > 0.30f) {
                            statusDisplay = "ANOMALY DETECTED"
                            statusColor = Color.Red
                        } else {
                            statusDisplay = "NORMAL RHYTHM"
                            statusColor = Color(0xFF4CAF50)
                        }
                    }
                }
            )
        }

        // --- UI LAYOUT ---
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("ECG AI Monitor", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(20.dp))

            Card(colors = CardDefaults.cardColors(containerColor = statusColor), modifier = Modifier.fillMaxWidth().height(80.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(statusDisplay, fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White)
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricCard("MSE Error", mseDisplay)
                MetricCard("Latency", latencyDisplay)
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { permissionLauncher.launch(permissionsToRequest) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE)),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("CONNECT STREAM", fontSize = 16.sp)
            }

            Spacer(Modifier.height(16.dp))
            Text("System Logs:", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.align(Alignment.Start))
            Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color(0xFF1E1E1E)).padding(8.dp).verticalScroll(scrollState)) {
                Text(logs, fontSize = 12.sp, color = Color.LightGray, lineHeight = 16.sp)
            }
        }
    }

    @Composable
    fun MetricCard(title: String, value: String) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C)), modifier = Modifier.width(160.dp).padding(4.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(title, color = Color.Gray, fontSize = 14.sp)
                Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ======================================================================
// MATHEMATICAL CLONE OF PYTHON SCIPY/NUMPY PIPELINE
// ======================================================================
object SignalProcessor {

    fun cleanSignal(input: FloatArray): FloatArray {
        // 1. np.nan_to_num (Replace NaNs and Infs)
        val cleaned = FloatArray(input.size) { i ->
            if (input[i].isNaN() || input[i].isInfinite()) 0.0f else input[i]
        }

        // 2. scipy.signal.detrend(type='linear')
        val detrended = linearDetrend(cleaned)

        // 3. scipy.signal.savgol_filter(window_length=11, polyorder=3)
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
        if (denominator == 0f) return y // Fallback to avoid division by zero

        val m = ((n * sumXY) - (sumX * sumY)) / denominator
        val c = (sumY - (m * sumX)) / n

        return FloatArray(y.size) { i -> y[i] - ((m * i.toFloat()) + c) }
    }

    private fun savgolFilter11Tap(data: FloatArray): FloatArray {
        // Exact mathematical coefficients for SG window=11, poly=3
        val coeffs = floatArrayOf(-36f, 9f, 44f, 69f, 84f, 89f, 84f, 69f, 44f, 9f, -36f)
        val norm = 429f
        val result = FloatArray(data.size)
        val halfWindow = 5

        for (i in data.indices) {
            var sum = 0f
            for (j in -halfWindow..halfWindow) {
                // Edge padding: copy nearest valid value
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
}