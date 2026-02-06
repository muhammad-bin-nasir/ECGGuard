package com.example.ecgguard

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private var bleManager: BleLatencyManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LatencyScreen()
            }
        }
    }

    @Composable
    fun LatencyScreen() {
        var logs by remember { mutableStateOf("Ready to test.") }
        val scrollState = rememberScrollState()

        // Define permissions based on Android version
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
            val allGranted = perms.values.all { it }
            if (allGranted) {
                logs += "\nPermissions Granted. Starting Scan..."
                bleManager?.connectAndTest()
            } else {
                logs += "\nPermissions Denied. Cannot scan."
            }
        }

        // Init Manager
        if (bleManager == null) {
            bleManager = BleLatencyManager(this) { msg ->
                runOnUiThread { logs += "\n$msg" }
            }
        }

        Column(modifier = Modifier.padding(20.dp)) {
            Text("ECG Latency Test", fontSize = 24.sp)
            Spacer(Modifier.height(20.dp))

            Button(onClick = { permissionLauncher.launch(permissionsToRequest) }) {
                Text("Connect & Start Ping")
            }

            Spacer(Modifier.height(20.dp))

            Text("Logs:", fontSize = 18.sp)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                Text(logs, fontSize = 14.sp)
            }
        }
    }
}