package online.automint.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.sentry.android.core.SentryAndroid
import online.automint.app.telemetry.TelemetryClient

class AutomintApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val sentryDsn = "https://f35847ac5cad489214a533a10c1f9d8a@o4511196684288000.ingest.de.sentry.io/4511234009071696"
        if (sentryDsn.isNotBlank()) {
            SentryAndroid.init(this) { options ->
                options.dsn = sentryDsn
                options.isDebug = BuildConfig.IS_DEV
                options.tracesSampleRate = if (BuildConfig.IS_DEV) 1.0 else 0.1
            }
        }

        val telemetry = TelemetryClient.getInstance(this)
        telemetry.installCrashHandler(Thread.getDefaultUncaughtExceptionHandler())

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                telemetry.onForeground()
            }

            override fun onStop(owner: LifecycleOwner) {
                telemetry.onBackground()
            }
        })

        createDefaultNotificationChannel()
    }

    private fun createDefaultNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        val channel = NotificationChannel(
            DEFAULT_CHANNEL_ID,
            getString(R.string.notification_channel_default),
            NotificationManager.IMPORTANCE_HIGH
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val DEFAULT_CHANNEL_ID = "automint_default_v2"
        private const val LEGACY_CHANNEL_ID = "automint_default"
    }
}
