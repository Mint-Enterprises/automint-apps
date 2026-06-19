package online.automint.app.security

import android.os.SystemClock
import online.automint.app.settings.SettingsStore

class AppLockController(private val settings: SettingsStore) {

    private var backgroundedAt: Long = 0L
    private var authenticated: Boolean = false

    fun onAppBackgrounded() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    fun onAppForegrounded() {
        if (!settings.biometricLockEnabled) {
            authenticated = true
            return
        }
        if (!authenticated) return

        val elapsedSec = (SystemClock.elapsedRealtime() - backgroundedAt) / 1000L
        if (backgroundedAt > 0 && elapsedSec >= settings.biometricLockTimeoutSeconds) {
            authenticated = false
        }
    }

    fun shouldPrompt(): Boolean = settings.biometricLockEnabled && !authenticated

    fun markAuthenticated() {
        authenticated = true
    }

    fun invalidate() {
        authenticated = false
    }
}
