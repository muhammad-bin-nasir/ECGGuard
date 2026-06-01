package com.example.ecgguard

import android.content.Context

data class OpenWaSettings(
    val enabled: Boolean,
    val serverUrl: String,   // e.g. "http://192.168.1.100:3000"
    val sessionId: String,   // session created in OpenWA dashboard
    val apiKey: String       // operator-level API key from OpenWA
)

object OpenWaConfig {
    private const val PREF_NAME = "ecg_prefs"
    private const val KEY_ENABLED  = "openwa_enabled"
    private const val KEY_URL      = "openwa_server_url"
    private const val KEY_SESSION  = "openwa_session_id"
    private const val KEY_APIKEY   = "openwa_api_key"

    fun get(context: Context): OpenWaSettings {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return OpenWaSettings(
            enabled   = prefs.getBoolean(KEY_ENABLED, false),
            serverUrl = prefs.getString(KEY_URL, "") ?: "",
            sessionId = prefs.getString(KEY_SESSION, "") ?: "",
            apiKey    = prefs.getString(KEY_APIKEY, "") ?: ""
        )
    }

    fun save(context: Context, settings: OpenWaSettings) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_URL, settings.serverUrl.trimEnd('/'))
            .putString(KEY_SESSION, settings.sessionId.trim())
            .putString(KEY_APIKEY, settings.apiKey.trim())
            .apply()
    }
}
