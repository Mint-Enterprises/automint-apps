package online.automint.app.bridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebStorage
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import online.automint.app.BuildConfig
import online.automint.app.MainActivity
import online.automint.app.R
import online.automint.app.identity.DeviceIdentity
import online.automint.app.settings.SettingsActivity
import online.automint.app.settings.SettingsStore
import java.util.concurrent.atomic.AtomicInteger

class AutomintBridge(
    private val context: Context,
    private val settings: SettingsStore,
    private val onReload: () -> Unit = {},
    private val onClearSession: () -> Unit = {},
    private val onScrollAtTopChanged: (Boolean) -> Unit = {},
    private val originAllowed: () -> Boolean = { false },
    private val fingerprintAllowed: () -> Boolean = { true },
) {

    private val main = Handler(Looper.getMainLooper())

    private fun isFirstParty(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return originAllowed()
        val result = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post {
            result.set(runCatching { originAllowed() }.getOrDefault(false))
            latch.countDown()
        }
        return if (latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) result.get() else false
    }

    @JavascriptInterface
    fun openSettings() {
        val intent = Intent(context, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    @JavascriptInterface
    fun appVersion(): String = BuildConfig.VERSION_NAME

    @JavascriptInterface
    fun appVersionCode(): Int = BuildConfig.VERSION_CODE

    @JavascriptInterface
    fun platform(): String = "android"

    @JavascriptInterface
    fun getFingerprint(): String? {
        if (!isFirstParty() || !fingerprintAllowed()) return null
        return runCatching { DeviceIdentity.get(context).fingerprintJson() }.getOrNull()
    }

    @JavascriptInterface
    fun setPageAtTop(atTop: Boolean) {
        onScrollAtTopChanged(atTop)
    }

    @JavascriptInterface
    fun isSpellCheckEnabled(): Boolean = settings.spellCheckEnabled

    @JavascriptInterface
    fun isBiometricLockEnabled(): Boolean = settings.biometricLockEnabled

    @JavascriptInterface
    fun reload() {
        main.post { onReload() }
    }

    @JavascriptInterface
    fun openReauthInBrowser(url: String?) {
        val uri = runCatching { Uri.parse(url ?: return) }.getOrNull() ?: return
        val host = uri.host?.lowercase() ?: return
        val allowed = host == "automint.online" || host.endsWith(".automint.online")
        if (!allowed || uri.scheme != "https") return
        main.post {
            val tabs = CustomTabsIntent.Builder().setShowTitle(true).build()
            runCatching { tabs.launchUrl(context, uri) }
        }
    }

    @JavascriptInterface
    fun clearSession() {
        main.post {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            onClearSession()
        }
    }

    @JavascriptInterface
    fun showNotification(title: String?, body: String?) = showNotification(title, body, null)

    @JavascriptInterface
    fun showNotification(title: String?, body: String?, deepLink: String?) {
        val t = title?.takeIf { it.isNotBlank() } ?: return
        val b = body.orEmpty()
        main.post {
            ensureChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return@post
            }
            val notifId = nextNotificationId()
            val tapIntent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            deepLink?.takeIf { it.isNotBlank() }?.let { link ->
                runCatching { Uri.parse(link) }.getOrNull()
                    ?.takeIf { it.scheme == "automint" }
                    ?.let { tapIntent.data = it }
            }
            val pi = PendingIntent.getActivity(
                context, notifId, tapIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notif = NotificationCompat.Builder(context, CHANNEL_LOCAL)
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.ic_splash_logo))
                .setColor(ContextCompat.getColor(context, R.color.am_primary))
                .setContentTitle(t)
                .setContentText(b)
                .setStyle(NotificationCompat.BigTextStyle().bigText(b))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(notifId, notif)
            online.automint.app.telemetry.TelemetryClient.getInstance(context)
                .track(EVENT_NOTIFICATION_SHOWN)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_LOCAL) != null) return
        mgr.deleteNotificationChannel(LEGACY_CHANNEL_LOCAL)
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LOCAL,
                context.getString(R.string.notif_channel_local),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    private fun nextNotificationId(): Int = notifIdSeq.incrementAndGet()

    companion object {
        const val NAME = "AutomintNative"
        private const val CHANNEL_LOCAL = "automint.local.v2"
        private const val LEGACY_CHANNEL_LOCAL = "automint.local"
        private const val EVENT_NOTIFICATION_SHOWN = "notification.shown"

        private val notifIdSeq = AtomicInteger(2001)
    }
}
