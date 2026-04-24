package com.example.ecgguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class EmergencyContact(val name: String, val phone: String, val apiKey: String = "")

object EmergencyContactStore {
    private const val PREF_NAME = "ecg_prefs"
    private const val KEY_CONTACTS = "emergency_contacts"

    fun getContacts(context: Context): List<EmergencyContact> {
        val json = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CONTACTS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                EmergencyContact(
                    obj.getString("name"),
                    obj.getString("phone"),
                    obj.optString("apiKey", "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveContacts(context: Context, contacts: List<EmergencyContact>) {
        val arr = JSONArray()
        contacts.forEach {
            arr.put(JSONObject().apply {
                put("name", it.name)
                put("phone", it.phone)
                put("apiKey", it.apiKey)
            })
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CONTACTS, arr.toString()).apply()
    }
}
