package online.automint.app.settings

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

class SettingsStore(context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, DEFAULT_NOTIFICATIONS)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATIONS, value) }

    var spellCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPELLCHECK, DEFAULT_SPELLCHECK)
        set(value) = prefs.edit { putBoolean(KEY_SPELLCHECK, value) }

    var biometricLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_LOCK, DEFAULT_BIOMETRIC_LOCK)
        set(value) = prefs.edit { putBoolean(KEY_BIOMETRIC_LOCK, value) }

    var biometricLockTimeoutSeconds: Int
        get() = prefs.getString(KEY_BIOMETRIC_TIMEOUT, DEFAULT_BIOMETRIC_TIMEOUT.toString())
            ?.toIntOrNull() ?: DEFAULT_BIOMETRIC_TIMEOUT
        set(value) = prefs.edit { putString(KEY_BIOMETRIC_TIMEOUT, value.toString()) }

    companion object {
        const val KEY_NOTIFICATIONS = "pref_notifications"
        const val KEY_SPELLCHECK = "pref_spellcheck"
        const val KEY_BIOMETRIC_LOCK = "pref_biometric_lock"
        const val KEY_BIOMETRIC_TIMEOUT = "pref_biometric_timeout_seconds"

        const val DEFAULT_NOTIFICATIONS = true
        const val DEFAULT_SPELLCHECK = true
        const val DEFAULT_BIOMETRIC_LOCK = false
        const val DEFAULT_BIOMETRIC_TIMEOUT = 30
    }
}
