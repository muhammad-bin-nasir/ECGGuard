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
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    // The Manager handles the Bluetooth background thread
    private var streamManager: BleStreamManager? = null

    // The Model handles the AI prediction
    private var model: ECGModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Model (Load ONNX file)
        try {
            model = ECGModel(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setContent {
            MaterialTheme {
                // A dark theme looks more "Medical" and readable
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

        // --- PERMISSIONS (Android 12+ vs Old) ---
        val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { perms ->
            if (perms.values.all { it }) {
                logs += "\nPermissions Granted. Connecting..."
                streamManager?.connect()
                statusDisplay = "SCANNING"
                statusColor = Color(0xFFFF9800) // Orange
            } else {
                logs += "\nPermissions Denied."
            }
        }

        // --- INITIALIZE STREAM MANAGER ---
        if (streamManager == null) {
            streamManager = BleStreamManager(
                context = this,
                onLog = { msg ->
                    runOnUiThread { logs += "\n$msg" }
                },
                onDataReceived = { inputData, debugMsg ->
                    // --- THIS RUNS WHEN BUFFER IS FULL (Every ~1 second) ---

                    runOnUiThread {
                        statusDisplay = "PROCESSING"
                        statusColor = Color(0xFF2196F3) // Blue
                    }

                    // 1. Measure Start Time
                    val tStart = System.nanoTime()

                    // 2. Preprocess (Clean Signal)
                    val cleanData = try {
                        DSPUtils.preprocess(inputData) ?: inputData
                    } catch (e: Exception) {
                        inputData
                    }

                    // 3. Inference (Run ONNX Model)
                    var mse = 0f
                    if (model != null) {
                        try {
                            mse = model!!.calculateMSE(cleanData)
                        } catch (e: Exception) {
                            logs += "\nModel Error: ${e.message}"
                        }
                    }

                    // 4. Measure End Time
                    val tEnd = System.nanoTime()

                    // Convert nanoseconds to milliseconds (More precise)
                    val inferenceTimeMs = (tEnd - tStart) / 1_000_000.0

                    // 5. Update UI
                    runOnUiThread {
                        mseDisplay = String.format("%.4f", mse)
                        latencyDisplay = String.format("%.2f ms", inferenceTimeMs)

                        logs += "\n[Prediction] MSE: $mseDisplay | Time: $latencyDisplay"

                        // Set Status based on MSE (Anomaly Detection)
                        if (mse > 0.5f) { // Threshold for "Abnormal"
                            statusDisplay = "ANOMALY DETECTED"
                            statusColor = Color.Red
                        } else {
                            statusDisplay = "NORMAL RHYTHM"
                            statusColor = Color(0xFF4CAF50) // Green
                        }
                    }
                }
            )
        }

        // --- UI LAYOUT ---
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("ECG AI Monitor", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(20.dp))

            // 1. STATUS CARD
            Card(
                colors = CardDefaults.cardColors(containerColor = statusColor),
                modifier = Modifier.fillMaxWidth().height(80.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(statusDisplay, fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White)
                }
            }

            Spacer(Modifier.height(16.dp))

            // 2. DATA METRICS ROW
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricCard("MSE Error", mseDisplay)
                MetricCard("Latency", latencyDisplay)
            }

            Spacer(Modifier.height(24.dp))

            // 3. CONTROLS
            Button(
                onClick = { permissionLauncher.launch(permissionsToRequest) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE)),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("CONNECT STREAM", fontSize = 16.sp)
            }

            Spacer(Modifier.height(16.dp))

            // 4. SCROLLING LOGS
            Text("System Logs:", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.align(Alignment.Start))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f) // Takes remaining space
                    .background(Color(0xFF1E1E1E))
                    .padding(8.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(logs, fontSize = 12.sp, color = Color.LightGray, lineHeight = 16.sp)
            }
        }
    }

    @Composable
    fun MetricCard(title: String, value: String) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C)),
            modifier = Modifier.width(160.dp).padding(4.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(title, color = Color.Gray, fontSize = 14.sp)
                Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}