package com.example.ecgguard

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class BackgroundMonitoringService : Service() {

    private var streamManager: BleStreamManager? = null
    private var lastHeartRate = 0
    private var bradyAlertActive = false

    private fun setBgMonitoringEnabled(enabled: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BG_MONITORING_ENABLED, enabled)
            .apply()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(NOTIF_ID, buildPersistentNotification("Starting monitor..."))
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                setBgMonitoringEnabled(false)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                setBgMonitoringEnabled(true)
                startMonitoring()
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startMonitoring() {
        if (streamManager != null) {
            updatePersistentNotification("Monitoring • HR: $lastHeartRate BPM")
            return
        }

        streamManager = BleStreamManager(
            context = this,
            onLog = { msg ->
                if (msg.contains("Disconnected") || msg.contains("timeout", ignoreCase = true)) {
                    updatePersistentNotification("Monitoring • Reconnecting...")
                }
            },
            onDataReceived = { inputData, _ ->
                val mvData = FloatArray(inputData.size) { i -> inputData[i] * 0.001f }
                val cleanData = SignalProcessor.cleanSignal(mvData)
                if (!SignalProcessor.isMechanicallySound(cleanData)) return@BleStreamManager

                val mu = cleanData.average().toFloat()
                val centeredData = FloatArray(cleanData.size) { i -> cleanData[i] - mu }
                val heartRate = SignalProcessor.calculateHeartRate(centeredData)

                if (heartRate in 30..220) {
                    lastHeartRate = heartRate
                    updatePersistentNotification("Monitoring • HR: $heartRate BPM")

                    if (heartRate < BRADY_THRESHOLD && !bradyAlertActive) {
                        bradyAlertActive = true
                        sendBradyNotification(heartRate)
                    } else if (heartRate >= BRADY_THRESHOLD) {
                        bradyAlertActive = false
                    }
                }
            }
        )

        try {
            streamManager?.connect()
        } catch (_: Exception) {
            updatePersistentNotification("Monitoring failed. Open app to grant permissions.")
            stopSelf()
        }
    }

    override fun onDestroy() {
        streamManager?.disconnect()
        streamManager = null
        setBgMonitoringEnabled(false)
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val monitorChannel = NotificationChannel(
                CHANNEL_ID,
                "ECG Background Monitoring",
                NotificationManager.IMPORTANCE_LOW
            )
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "ECG Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(monitorChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun updatePersistentNotification(status: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, buildPersistentNotification(status))
    }

    private fun buildPersistentNotification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, BackgroundMonitoringService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ECGGuard running in background")
            .setContentText(status)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun sendBradyNotification(heartRate: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = PendingIntent.getActivity(
            this,
            12,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Bradycardia detected")
            .setContentText("Heart rate dropped to $heartRate BPM")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        manager.notify(ALERT_NOTIF_ID, notif)
    }

    companion object {
        const val ACTION_START = "ecgguard.action.START_BACKGROUND_MONITORING"
        const val ACTION_STOP = "ecgguard.action.STOP_BACKGROUND_MONITORING"
        const val PREFS_NAME = "ecgguard_prefs"
        const val KEY_BG_MONITORING_ENABLED = "bg_monitoring_enabled"

        private const val CHANNEL_ID = "ecgguard_bg_monitor"
        private const val ALERT_CHANNEL_ID = "ecgguard_alerts"
        private const val NOTIF_ID = 9001
        private const val ALERT_NOTIF_ID = 9002
        private const val BRADY_THRESHOLD = 50
    }
}
