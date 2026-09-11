package com.example.ecgguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * EmergencyContactStore.kt
 * ========================
 * Handles saving and loading emergency contacts to/from persistent storage.
 *
 * STORAGE MECHANISM
 * ─────────────────
 * Contacts are stored in Android SharedPreferences as a single JSON array string.
 * SharedPreferences survives app restarts and app updates, but is deleted
 * if the user uninstalls the app.
 *
 * Example of what gets stored:
 *   [{"name":"Ali Khan","phone":"+923001234567"},{"name":"Sana","phone":"+923331234567"}]
 *
 * HOW TO ADD A NEW FIELD TO A CONTACT
 * ────────────────────────────────────
 * 1. Add the field to the EmergencyContact data class below (e.g., val email: String)
 * 2. In saveContacts(), add:  put("email", it.email)
 * 3. In getContacts(), add:  obj.optString("email", "")  (optString avoids crash on old saves)
 *
 * HOW TO SWITCH TO A DATABASE (Room) INSTEAD
 * ──────────────────────────────────────────
 * Replace getContacts()/saveContacts() with Room DAO calls.
 * Keep the EmergencyContact data class as-is — it can be used as a Room Entity.
 */

/**
 * Represents one emergency contact.
 *
 * @param name  Display name shown in the contacts list and included in alert messages.
 *              Change this to add more fields (email, relationship, etc.)
 * @param phone Phone number in international format, e.g. "+923001234567".
 *              Used to build the WhatsApp chatId: digits only + "@c.us"
 *              Also used as the SMS destination in sendWhatsAppAlerts().
 */
data class EmergencyContact(val name: String, val phone: String)

/**
 * Singleton object — call EmergencyContactStore.getContacts(context) from anywhere.
 * No need to instantiate. Safe to call from any thread (SharedPreferences reads are
 * synchronous but fast; writes use .apply() which is asynchronous).
 */
object EmergencyContactStore {

    // ── Storage keys ──────────────────────────────────────────────────────────

    /**
     * SharedPreferences file name.
     * Both EmergencyContactStore and OpenWaConfig share this same file.
     * HOW TO CHANGE: rename this string if you want contacts in a separate prefs file.
     * If you rename it, existing saved contacts will appear empty on the next launch
     * (they won't be migrated automatically).
     */
    private const val PREF_NAME = "ecg_prefs"

    /**
     * The key under which the JSON array of contacts is stored.
     * HOW TO CHANGE: rename this if you add versioning (e.g., "emergency_contacts_v2")
     * to distinguish old saves from new ones during a migration.
     */
    private const val KEY_CONTACTS = "emergency_contacts"

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Loads and returns all saved emergency contacts.
     *
     * Returns an empty list (not null) if:
     *   - No contacts have been saved yet
     *   - The stored JSON is corrupted or unparseable
     *
     * @param context  Any Context (Activity, Service, Application) — used to access SharedPreferences.
     * @return         List of EmergencyContact, possibly empty, never null.
     */
    fun getContacts(context: Context): List<EmergencyContact> {
        // Load the raw JSON string; default to "[]" (empty array) if nothing saved yet
        val json = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CONTACTS, "[]") ?: "[]"

        return try {
            val arr = JSONArray(json)
            // Map each JSON object to an EmergencyContact data class
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                EmergencyContact(
                    name  = obj.getString("name"),
                    phone = obj.getString("phone")
                )
            }
        } catch (e: Exception) {
            // If the JSON is malformed (e.g., corrupted prefs), return empty list safely
            emptyList()
        }
    }

    /**
     * Saves the given list of contacts, replacing whatever was stored before.
     *
     * This is a full overwrite — to add one contact, pass the full updated list:
     *   EmergencyContactStore.saveContacts(ctx, existingList + newContact)
     *
     * To delete one contact, filter it out:
     *   EmergencyContactStore.saveContacts(ctx, existingList.filter { it.phone != toDelete.phone })
     *
     * @param context   Any Context.
     * @param contacts  The complete list to save. Pass an empty list to clear all contacts.
     */
    fun saveContacts(context: Context, contacts: List<EmergencyContact>) {
        val arr = JSONArray()
        contacts.forEach {
            // Build a JSON object for each contact with "name" and "phone" keys
            arr.put(JSONObject().apply {
                put("name",  it.name)
                put("phone", it.phone)
            })
        }
        // .apply() writes asynchronously (won't block the UI thread)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CONTACTS, arr.toString()).apply()
    }
}
