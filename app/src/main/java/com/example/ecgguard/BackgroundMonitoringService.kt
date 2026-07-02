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
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * BackgroundMonitoringService.kt
 * ===============================
 * An Android Foreground Service that keeps ECG monitoring alive when the
 * app is in the background or the screen is locked.
 *
 * WHY A FOREGROUND SERVICE?
 * ─────────────────────────
 * Android aggressively kills background processes to save battery.
 * A Foreground Service is exempt from this — it stays alive indefinitely
 * as long as it shows a persistent notification (the "ECGGuard running in
 * background" notification that appears in the status bar).
 *
 * WHAT THIS SERVICE DOES VS WHAT MAINACTIVITY DOES
 * ──────────────────────────────────────────────────
 *   MainActivity   : Full pipeline — BLE + signal cleaning + ONNX model + all UI
 *   This Service   : Simplified pipeline — BLE + signal cleaning + heart rate only
 *                    (no ONNX inference to save battery in background)
 *
 * The service intentionally skips the ONNX model to reduce background battery use.
 * HOW TO ADD MODEL IN BACKGROUND: create an ECGModel instance in onCreate() and
 * call model.runInference(centeredData) in the onDataReceived callback below.
 *
 * LIFECYCLE
 * ─────────
 *   START: User taps "START BG" in SettingsScreen →
 *          MainActivity calls ContextCompat.startForegroundService(intent with ACTION_START)
 *   STOP:  User taps "STOP BG" in SettingsScreen →
 *          MainActivity calls stopService()
 *          OR user taps the "Stop" button on the persistent notification
 *          → ACTION_STOP is handled in onStartCommand
 *
 * HOW TO ADD TACHYCARDIA DETECTION
 * ─────────────────────────────────
 * Below the bradycardia check (heartRate < BRADY_THRESHOLD), add:
 *   val TACHY_THRESHOLD = 120   // BPM
 *   if (heartRate > TACHY_THRESHOLD && !tachyAlertActive) {
 *       tachyAlertActive = true
 *       sendTachyNotification(heartRate)
 *   } else if (heartRate <= TACHY_THRESHOLD) {
 *       tachyAlertActive = false
 *   }
 * Then implement sendTachyNotification() similarly to sendBradyNotification().
 *
 * HOW TO ADD ANOMALY DETECTION IN BACKGROUND
 * ────────────────────────────────────────────
 * 1. Add: private var model: ECGModel? = null
 * 2. In onCreate(): try { model = ECGModel(this) } catch (e: Exception) { }
 * 3. In onDataReceived: val (mse, _) = model?.runInference(centeredData) ?: return
 *    if (mse > 0.30f) sendAnomalyNotification()
 * 4. Don't forget to close the model in onDestroy(): model = null
 */
class BackgroundMonitoringService : Service() {

    // ── Instance fields ────────────────────────────────────────────────────────

    /** The BLE connection manager. Created fresh each time the service starts. */
    private var streamManager: BleStreamManager? = null

    /** Last valid heart rate — shown in the persistent notification. */
    private var lastHeartRate = 0

    /**
     * True while a bradycardia episode is ongoing.
     * Prevents sending repeated notifications for the same episode.
     * Resets to false when HR returns to or above BRADY_THRESHOLD.
     */
    private var bradyAlertActive = false

    /**
     * Coroutine scope for background HTTP calls (WhatsApp alerts).
     * SupervisorJob: if one coroutine (one HTTP call) fails, others continue.
     * Dispatchers.IO: uses a thread pool optimised for blocking I/O.
     */
    private val serviceJob   = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)

    // ── Service lifecycle ──────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null   // not a bound service

    override fun onCreate() {
        super.onCreate()
        // Create notification channels before calling startForeground().
        // On Android 8+, the notification channel must exist before posting.
        ensureNotificationChannel()
        // startForeground() is REQUIRED within 5 seconds of service creation on Android 9+.
        // Without it, the OS throws a RemoteServiceException and kills the service.
        startForeground(NOTIF_ID, buildPersistentNotification("Starting monitor..."))
    }

    /**
     * Called every time startForegroundService() is called on this service.
     * Also handles the STOP action from the notification's "Stop" button.
     *
     * START_STICKY: if the OS kills this service (low memory), it restarts
     * automatically with a null intent.
     * HOW TO CHANGE: use START_NOT_STICKY if you don't want auto-restart.
     */
    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // User tapped "Stop" on the notification — stop cleanly
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

    override fun onDestroy() {
        streamManager?.disconnect()
        streamManager = null
        setBgMonitoringEnabled(false)
        serviceJob.cancel()   // cancel all pending HTTP coroutines
        super.onDestroy()
    }

    // ── Monitoring logic ───────────────────────────────────────────────────────

    /**
     * Creates a BleStreamManager and connects it to the ESP32.
     * No-op if already monitoring (guards against duplicate connections).
     *
     * SIMPLIFIED PIPELINE (no ONNX model):
     *   raw ADC → millivolts → cleanSignal → isMechanicallySound gate
     *   → mean-centre → calculateHeartRate → bradycardia check
     */
    @SuppressLint("MissingPermission")
    private fun startMonitoring() {
        if (streamManager != null) {
            updatePersistentNotification("Monitoring • HR: $lastHeartRate BPM")
            return
        }

        streamManager = BleStreamManager(
            context = this,
            onLog = { msg ->
                // Update notification text when BLE connection drops or times out
                if (msg.contains("Disconnected") || msg.contains("timeout", ignoreCase = true)) {
                    updatePersistentNotification("Monitoring • Reconnecting...")
                }
            },
            onDataReceived = { inputData, _ ->

                // ── Step 1: Convert raw ADC to millivolts ─────────────────────
                // The ESP32 ADC returns 0–4095 (12-bit). Multiplying by 0.001
                // scales these to the ~0–4 mV range the signal processor expects.
                // HOW TO CHANGE: If the ESP32 sends pre-scaled mV values, use ×1.0f
                val mvData = FloatArray(inputData.size) { i -> inputData[i] * 0.001f }

                // ── Step 2: Clean the signal ──────────────────────────────────
                val cleanData = SignalProcessor.cleanSignal(mvData)

                // ── Step 3: Quality gate — skip noisy/disconnected windows ────
                if (!SignalProcessor.isMechanicallySound(cleanData)) return@BleStreamManager

                // ── Step 4: Mean-centre (required by the HR detection algorithm)
                val mu          = cleanData.average().toFloat()
                val centeredData = FloatArray(cleanData.size) { i -> cleanData[i] - mu }

                // ── Step 5: Calculate heart rate from R-peak spacing ──────────
                val heartRate = SignalProcessor.calculateHeartRate(centeredData)

                // ── Step 6: Update notification and check for bradycardia ─────
                if (heartRate in 30..220) {
                    lastHeartRate = heartRate
                    updatePersistentNotification("Monitoring • HR: $heartRate BPM")

                    // BRADYCARDIA CHECK
                    // HOW TO CHANGE THRESHOLD: change BRADY_THRESHOLD in companion object.
                    // Default: 50 BPM. Raise to catch more borderline cases.
                    // Lower to only alert on severe bradycardia.
                    if (heartRate < BRADY_THRESHOLD && !bradyAlertActive) {
                        bradyAlertActive = true
                        sendBradyNotification(heartRate)
                    } else if (heartRate >= BRADY_THRESHOLD) {
                        bradyAlertActive = false  // reset for next episode
                    }
                }
            }
        )

        try {
            streamManager?.connect()
        } catch (_: Exception) {
            // If connect() throws (e.g. missing BT permission in background),
            // show an error notification and stop the service cleanly.
            updatePersistentNotification("Monitoring failed. Open app to grant permissions.")
            stopSelf()
        }
    }

    // ── Notification management ────────────────────────────────────────────────

    /**
     * Creates the two notification channels required on Android 8+.
     * Safe to call multiple times — Android ignores duplicate channel creation.
     *
     * CHANNEL 1 (CHANNEL_ID): IMPORTANCE_LOW — no sound, shown in status bar.
     *   Used for the persistent "ECGGuard running" notification.
     *   HOW TO CHANGE: raise to IMPORTANCE_DEFAULT to add sound on each update.
     *
     * CHANNEL 2 (ALERT_CHANNEL_ID): IMPORTANCE_HIGH — heads-up notification with sound.
     *   Used for bradycardia alerts.
     *   HOW TO CHANGE: lower to IMPORTANCE_DEFAULT to reduce intrusiveness.
     */
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ECG Background Monitoring", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL_ID, "ECG Alerts", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    /** Updates the persistent foreground notification text without re-creating it. */
    private fun updatePersistentNotification(status: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, buildPersistentNotification(status))
    }

    /**
     * Builds the persistent foreground notification shown while the service is running.
     *
     * TWO ACTIONS available to the user from the notification:
     *   - Tap the notification → opens MainActivity
     *   - Tap "Stop" button   → sends ACTION_STOP to this service
     *
     * HOW TO ADD MORE ACTIONS: add more .addAction() calls with different intents.
     * HOW TO CHANGE NOTIFICATION ICON: replace R.mipmap.ic_launcher with a custom drawable.
     */
    private fun buildPersistentNotification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 11,
            Intent(this, BackgroundMonitoringService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ECGGuard running in background")
            .setContentText(status)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)              // user cannot swipe it away
            .setContentIntent(openIntent)  // tap → open app
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    /**
     * Fires a high-priority heads-up notification when bradycardia is detected,
     * then sends WhatsApp alerts to all emergency contacts.
     *
     * HOW TO ADD SMS FALLBACK IN BACKGROUND:
     * SMS intents require the app to be in the foreground (Android 10+).
     * In background, only the OpenWA HTTP path is available.
     * To support SMS from background, use SmsManager directly:
     *   SmsManager.getDefault().sendTextMessage(phone, null, message, null, null)
     * This requires the SEND_SMS permission in AndroidManifest.xml.
     */
    private fun sendBradyNotification(heartRate: Int) {
        val manager    = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = PendingIntent.getActivity(
            this, 12,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Bradycardia detected")
            .setContentText("Heart rate dropped to $heartRate BPM")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)          // dismissed when tapped
            .setContentIntent(openIntent)
            .build()

        manager.notify(ALERT_NOTIF_ID, notif)

        // Also send WhatsApp messages to all emergency contacts
        sendWhatsAppAlerts(heartRate)
    }

    /**
     * Sends WhatsApp messages to all saved emergency contacts via OpenWA REST API.
     * Each contact gets its own coroutine so one failed send doesn't block others.
     *
     * PHONE NUMBER FORMAT:
     * "+923001234567" → remove non-digits → "923001234567" → append "@c.us"
     * WhatsApp uses "countrycode+number@c.us" as the chat identifier.
     *
     * HOW TO CHANGE THE ALERT MESSAGE:
     * Modify the `text` string below. You can include heartRate, patient name, etc.
     *
     * HOW TO ADD LOCATION TO BACKGROUND ALERTS:
     * Add ACCESS_FINE_LOCATION permission and use LocationManager.getLastKnownLocation()
     * (same as sendWhatsAppAlerts() in MainActivity.kt for foreground alerts).
     * Background location requires ACCESS_BACKGROUND_LOCATION permission (Android 10+).
     */
    private fun sendWhatsAppAlerts(heartRate: Int) {
        val openWa = OpenWaConfig.get(this)

        // Only send if OpenWA is configured and enabled
        if (!openWa.enabled
            || openWa.serverUrl.isBlank()
            || openWa.sessionId.isBlank()
            || openWa.apiKey.isBlank()
        ) return

        val prefs       = getSharedPreferences("ecgguard_prefs", Context.MODE_PRIVATE)
        val patientName = prefs.getString("patient_name", "Patient") ?: "Patient"
        val contacts    = EmergencyContactStore.getContacts(this)
        if (contacts.isEmpty()) return

        val text = "ECGGuard ALERT: $patientName — BRADYCARDIA detected (HR: $heartRate BPM). Please check on them."

        contacts.forEach { contact ->
            // Build WhatsApp chatId: digits only + "@c.us"
            val chatId   = contact.phone.replace(Regex("[^0-9]"), "") + "@c.us"
            val endpoint = "${openWa.serverUrl}/api/sessions/${openWa.sessionId}/messages/send-text"
            val jsonBody = JSONObject().apply {
                put("chatId", chatId)
                put("text",   text)
            }.toString()

            // Each contact gets its own coroutine — failures are independent
            serviceScope.launch {
                var connection: HttpURLConnection? = null
                try {
                    connection = URL(endpoint).openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setRequestProperty("X-API-Key", openWa.apiKey)
                    connection.doOutput        = true
                    connection.connectTimeout  = 10_000   // 10 seconds to connect
                    connection.readTimeout     = 10_000   // 10 seconds to read response
                    connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(jsonBody) }

                    val code = connection.responseCode
                    if (code !in 200..299) {
                        // Log HTTP error body for debugging (e.g. invalid chatId format)
                        val err = connection.errorStream?.bufferedReader()?.readText() ?: "(no body)"
                        Log.e("ECGGuard-OpenWA", "BG service HTTP $code for $chatId — $err")
                    }
                } catch (e: Exception) {
                    Log.e("ECGGuard-OpenWA", "BG service failed to send to $chatId: ${e.message}", e)
                } finally {
                    connection?.disconnect()
                }
            }
        }
    }

    // ── SharedPreferences helper ───────────────────────────────────────────────

    /**
     * Writes the background monitoring enabled/disabled state to SharedPreferences.
     * MainActivity reads this on resume to sync the UI toggle with the actual service state.
     */
    private fun setBgMonitoringEnabled(enabled: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BG_MONITORING_ENABLED, enabled)
            .apply()
    }

    // ── Constants ──────────────────────────────────────────────────────────────

    companion object {
        /** Intent action to START background monitoring. Sent by MainActivity. */
        const val ACTION_START = "ecgguard.action.START_BACKGROUND_MONITORING"

        /** Intent action to STOP background monitoring. Sent by the notification "Stop" button. */
        const val ACTION_STOP  = "ecgguard.action.STOP_BACKGROUND_MONITORING"

        /** SharedPreferences file name shared with MainActivity for state sync. */
        const val PREFS_NAME = "ecgguard_prefs"

        /** SharedPreferences key storing whether background monitoring is currently active. */
        const val KEY_BG_MONITORING_ENABLED = "bg_monitoring_enabled"

        // Notification channel IDs (must be unique per app)
        private const val CHANNEL_ID       = "ecgguard_bg_monitor"  // persistent notification channel
        private const val ALERT_CHANNEL_ID = "ecgguard_alerts"       // alert notification channel

        // Notification IDs (must be unique per notification type)
        private const val NOTIF_ID       = 9001   // persistent foreground notification
        private const val ALERT_NOTIF_ID = 9002   // bradycardia alert notification

        /**
         * Bradycardia threshold in BPM.
         * If HR drops below this, a notification fires and WhatsApp alerts are sent.
         *
         * HOW TO CHANGE: update this constant. The UI slider in SettingsScreen
         * (bradyThreshold state variable in MainActivity) controls the threshold
         * for foreground alerts, but this constant controls the BACKGROUND threshold.
         * To make them share the same value, save the UI slider value to SharedPreferences
         * and read it here instead of using this constant:
         *   val brady = prefs.getInt("brady_threshold", 50)
         */
        private const val BRADY_THRESHOLD = 50
    }
}
