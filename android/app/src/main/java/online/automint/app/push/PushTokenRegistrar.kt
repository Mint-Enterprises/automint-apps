package online.automint.app.push

import android.content.Context
import android.webkit.CookieManager
import com.google.firebase.messaging.FirebaseMessaging
import java.net.HttpURLConnection
import java.net.URL
import online.automint.app.BuildConfig
import org.json.JSONObject

object PushTokenRegistrar {

    private const val PREFS = "push_registrar"
    private const val KEY_FINGERPRINT = "last_fingerprint"
    private const val REGISTER_PATH = "/api/push/register"
    private const val UNREGISTER_PATH = "/api/push/unregister"
    private const val TIMEOUT_MS = 15_000

    fun register(context: Context) {
        val app = context.applicationContext
        val cookie = sessionCookie() ?: return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            if (token.isNullOrBlank()) return@addOnSuccessListener
            val fingerprint = (token + "|" + cookie).hashCode().toString()
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_FINGERPRINT, null) == fingerprint) return@addOnSuccessListener
            Thread {
                val body = JSONObject()
                    .put("token", token)
                    .put("platform", "android")
                    .put("appVersion", BuildConfig.VERSION_NAME)
                    .toString()
                if (post(REGISTER_PATH, body, cookie)) {
                    prefs.edit().putString(KEY_FINGERPRINT, fingerprint).apply()
                }
            }.start()
        }
    }

    fun forget(context: Context) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_FINGERPRINT).apply()
        val cookie = sessionCookie() ?: return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            if (token.isNullOrBlank()) return@addOnSuccessListener
            Thread { post(UNREGISTER_PATH, JSONObject().put("token", token).toString(), cookie) }.start()
        }
    }

    private fun sessionCookie(): String? =
        CookieManager.getInstance().getCookie(BuildConfig.TARGET_URL)?.takeIf { it.isNotBlank() }

    private fun post(path: String, body: String, cookie: String): Boolean {
        val url = runCatching { URL(BuildConfig.TARGET_URL.trimEnd('/') + path) }.getOrNull() ?: return false
        if (!url.protocol.equals("https", ignoreCase = true)) return false
        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cookie", cookie)
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
