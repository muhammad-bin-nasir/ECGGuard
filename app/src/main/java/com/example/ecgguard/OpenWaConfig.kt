package com.example.ecgguard

import android.content.Context

/**
 * OpenWaConfig.kt
 * ===============
 * Saves and loads the self-hosted OpenWA WhatsApp API settings.
 *
 * WHAT IS OPENWA?
 * ───────────────
 * OpenWA (open-wa.dev) is a self-hosted server that lets you send WhatsApp
 * messages via a REST API. You run it on a PC on the same Wi-Fi network —
 * it authenticates as your WhatsApp account. ECGGuard POSTs an alert message
 * to it whenever an anomaly or bradycardia is detected.
 *
 * HOW TO ADD MORE CONFIG FIELDS
 * ─────────────────────────────
 * 1. Add a field to OpenWaSettings (e.g. val timeoutSeconds: Int = 10)
 * 2. Add a KEY constant below (e.g. private const val KEY_TIMEOUT = "openwa_timeout")
 * 3. Read it in get():  timeoutSeconds = prefs.getInt(KEY_TIMEOUT, 10)
 * 4. Write it in save(): .putInt(KEY_TIMEOUT, settings.timeoutSeconds)
 * 5. Add a UI control in the OpenWA card inside SettingsScreen in MainActivity.kt
 */

/**
 * Immutable snapshot of the user's OpenWA configuration.
 *
 * @param enabled    If false, no WhatsApp alerts fire regardless of other fields.
 *                   Toggle this cheaply without losing the rest of the config.
 * @param serverUrl  Base URL of the OpenWA server, e.g. "http://192.168.1.100:3000".
 *                   Must be reachable from the phone (same Wi-Fi, VPN, or port-forwarded).
 *                   Trailing slash stripped on save so URL joins work cleanly.
 *                   CHANGE THIS when the server IP or port changes.
 * @param sessionId  The session name created in the OpenWA dashboard after scanning the QR code.
 *                   CHANGE THIS if you create a new OpenWA session.
 * @param apiKey     Operator-level API key from the OpenWA dashboard.
 *                   Sent as the "X-API-Key" HTTP header on every request.
 *                   CHANGE THIS if you rotate the key or if it is compromised.
 */
data class OpenWaSettings(
    val enabled:      Boolean,
    val serverUrl:    String,
    val sessionId:    String,
    val apiKey:       String,
    /**
     * The alert message template sent to emergency contacts.
     * Supported placeholders — replaced automatically before sending:
     *   {name}      → patient name set on first launch
     *   {condition} → "ANOMALY DETECTED" or "BRADYCARDIA"
     *   {location}  → Google Maps link, or "(Location unavailable)"
     */
    val alertMessage: String = "ECGGuard ALERT: {name} — {condition}. Location: {location}"
)

/**
 * Singleton — call OpenWaConfig.get(ctx) / save(ctx, settings) from anywhere.
 * Shares the "ecg_prefs" SharedPreferences file with EmergencyContactStore.
 */
object OpenWaConfig {

    // SharedPreferences file name — shared with EmergencyContactStore.
    // If you rename this, old saved settings will be lost on next launch.
    private const val PREF_NAME = "ecg_prefs"

    // Individual preference keys — one per field in OpenWaSettings.
    // Rename a key only if you also clear/migrate the old prefs value.
    private const val KEY_ENABLED  = "openwa_enabled"       // Boolean
    private const val KEY_URL      = "openwa_server_url"    // String
    private const val KEY_SESSION  = "openwa_session_id"    // String
    private const val KEY_APIKEY   = "openwa_api_key"       // String
    private const val KEY_MSG      = "openwa_alert_message" // String
    private const val DEFAULT_MSG  = "ECGGuard ALERT: {name} — {condition}. Location: {location}"

    /**
     * Reads the saved OpenWA settings.
     * Returns defaults (enabled=false, all strings empty) if never saved.
     * Safe to call from any thread.
     */
    fun get(context: Context): OpenWaSettings {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return OpenWaSettings(
            enabled      = prefs.getBoolean(KEY_ENABLED, false),
            serverUrl    = prefs.getString(KEY_URL,     "") ?: "",
            sessionId    = prefs.getString(KEY_SESSION, "") ?: "",
            apiKey       = prefs.getString(KEY_APIKEY,  "") ?: "",
            alertMessage = prefs.getString(KEY_MSG, DEFAULT_MSG) ?: DEFAULT_MSG
        )
    }

    /**
     * Persists the given settings asynchronously (uses .apply(), non-blocking).
     * Trims whitespace from sessionId and apiKey to avoid user paste errors.
     * Strips trailing slash from serverUrl so URL joins always work correctly.
     *
     * TO DISABLE ALERTS WITHOUT LOSING CONFIG: save(ctx, settings.copy(enabled = false))
     */
    fun save(context: Context, settings: OpenWaSettings) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_URL,      settings.serverUrl.trimEnd('/'))
            .putString(KEY_SESSION,  settings.sessionId.trim())
            .putString(KEY_APIKEY,   settings.apiKey.trim())
            .putString(KEY_MSG,      settings.alertMessage.ifBlank { DEFAULT_MSG })
            .apply()
    }
}
