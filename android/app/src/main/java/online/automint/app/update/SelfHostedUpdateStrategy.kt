package online.automint.app.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import online.automint.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SelfHostedUpdateStrategy(
    context: Context,
    private val ui: UpdateUi,
) : UpdateStrategy {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun check(activity: Activity) {
        val current = BuildConfig.VERSION_CODE
        readCachedRelease()?.let { cached ->
            if (current < cached.latestVersionCode) {
                present(activity, current, cached)
            } else {
                clearCachedRelease()
                ui.hideUpdateAvailable()
            }
        }

        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return

        scope.launch {
            val manifest = withContext(Dispatchers.IO) { fetchManifest() } ?: return@launch
            prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

            val release = ReleaseInfo(
                latestVersionCode = manifest.optInt("latestVersionCode", 0),
                minSupportedVersionCode = manifest.optInt("minSupportedVersionCode", 0),
                versionName = manifest.optString("versionName", ""),
                apkUrl = manifest.optString("apkUrl", "").ifBlank { BuildConfig.DOWNLOAD_URL },
            )

            if (release.latestVersionCode > current) {
                cacheRelease(release)
                present(activity, current, release)
            } else {
                clearCachedRelease()
                ui.hideUpdateAvailable()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
    }

    private fun fetchManifest(): JSONObject? = runCatching {
        val conn = (URL(BuildConfig.UPDATE_MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun openDownload(activity: Activity, url: String) {
        runCatching {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun present(activity: Activity, current: Int, release: ReleaseInfo) {
        if (current < release.minSupportedVersionCode) {
            ui.showUpdateRequired(release.versionName) {
                openDownload(activity, release.apkUrl)
            }
        } else {
            ui.showUpdateAvailable(release.versionName) {
                openDownload(activity, release.apkUrl)
            }
        }
    }

    private fun readCachedRelease(): ReleaseInfo? {
        val latestVersionCode = prefs.getInt(KEY_LATEST_VERSION_CODE, 0)
        if (latestVersionCode <= 0) return null
        return ReleaseInfo(
            latestVersionCode = latestVersionCode,
            minSupportedVersionCode = prefs.getInt(KEY_MIN_SUPPORTED_VERSION_CODE, 0),
            versionName = prefs.getString(KEY_VERSION_NAME, "").orEmpty(),
            apkUrl = prefs.getString(KEY_APK_URL, BuildConfig.DOWNLOAD_URL)
                .orEmpty()
                .ifBlank { BuildConfig.DOWNLOAD_URL },
        )
    }

    private fun cacheRelease(release: ReleaseInfo) {
        prefs.edit()
            .putInt(KEY_LATEST_VERSION_CODE, release.latestVersionCode)
            .putInt(KEY_MIN_SUPPORTED_VERSION_CODE, release.minSupportedVersionCode)
            .putString(KEY_VERSION_NAME, release.versionName)
            .putString(KEY_APK_URL, release.apkUrl)
            .apply()
    }

    private fun clearCachedRelease() {
        prefs.edit()
            .remove(KEY_LATEST_VERSION_CODE)
            .remove(KEY_MIN_SUPPORTED_VERSION_CODE)
            .remove(KEY_VERSION_NAME)
            .remove(KEY_APK_URL)
            .apply()
    }

    private data class ReleaseInfo(
        val latestVersionCode: Int,
        val minSupportedVersionCode: Int,
        val versionName: String,
        val apkUrl: String,
    )

    companion object {
        private const val PREFS = "update_state"
        private const val KEY_LAST_CHECK = "last_check_ms"
        private const val KEY_LATEST_VERSION_CODE = "latest_version_code"
        private const val KEY_MIN_SUPPORTED_VERSION_CODE = "min_supported_version_code"
        private const val KEY_VERSION_NAME = "version_name"
        private const val KEY_APK_URL = "apk_url"
        private const val CHECK_INTERVAL_MS = 4L * 60 * 60 * 1000
        private const val TIMEOUT_MS = 8000
    }
}
