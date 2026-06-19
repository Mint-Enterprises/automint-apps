package online.automint.app.web

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.edit
import online.automint.app.BuildConfig
import kotlin.math.abs

class LastLocationStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(url: String?) {
        val eligible = url?.takeIf { isRestorable(it) } ?: return
        prefs.edit {
            putString(KEY_URL, eligible)
            putLong(KEY_BOOT, bootStamp())
        }
    }

    fun restorableUrl(): String? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        val savedBoot = prefs.getLong(KEY_BOOT, 0L)
        if (savedBoot == 0L || abs(bootStamp() - savedBoot) > BOOT_TOLERANCE_MS) {
            clear()
            return null
        }
        return url.takeIf { isRestorable(it) }
    }

    fun clear() = prefs.edit { remove(KEY_URL).remove(KEY_BOOT) }

    private fun bootStamp(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private fun isRestorable(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val targetHost = runCatching { Uri.parse(BuildConfig.TARGET_URL).host }.getOrNull()
        if (!uri.host.equals(targetHost, ignoreCase = true)) return false
        val path = uri.path.orEmpty()
        return AUTH_PREFIXES.none { path.startsWith(it) }
    }

    companion object {
        private const val PREFS = "last_location"
        private const val KEY_URL = "url"
        private const val KEY_BOOT = "boot_stamp"

        private const val BOOT_TOLERANCE_MS = 10_000L

        private val AUTH_PREFIXES = listOf("/login", "/reauth", "/auth", "/api")
    }
}
