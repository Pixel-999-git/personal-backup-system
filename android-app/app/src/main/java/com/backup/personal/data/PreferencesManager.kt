package com.backup.personal.data

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)

    var serverHost: String
        get() = prefs.getString("server_host", "192.168.1.100") ?: "192.168.1.100"
        set(value) = prefs.edit().putString("server_host", value.trim()).apply()

    var serverPort: Int
        get() = prefs.getInt("server_port", 8976)
        set(value) = prefs.edit().putInt("server_port", value).apply()

    var authToken: String?
        get() = prefs.getString("auth_token", null)
        set(value) = prefs.edit().putString("auth_token", value).apply()

    var deviceId: String
        get() {
            var id = prefs.getString("device_id", null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString("device_id", id).apply()
            }
            return id
        }
        set(value) = prefs.edit().putString("device_id", value).apply()

    var deviceName: String
        get() = prefs.getString("device_name", "Samsung Galaxy A05s") ?: "Samsung Galaxy A05s"
        set(value) = prefs.edit().putString("device_name", value).apply()

    var isPaired: Boolean
        get() = !authToken.isNullOrBlank()
        set(value) {
            if (!value) authToken = null
        }

    var autoBackupEnabled: Boolean
        get() = prefs.getBoolean("auto_backup_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_backup_enabled", value).apply()

    var lastReconciliationTime: Long
        get() = prefs.getLong("last_reconciliation_time", 0L)
        set(value) = prefs.edit().putLong("last_reconciliation_time", value).apply()

    var hasRequestedInitialPermissions: Boolean
        get() = prefs.getBoolean("has_requested_initial_permissions", false)
        set(value) = prefs.edit().putBoolean("has_requested_initial_permissions", value).apply()
}
