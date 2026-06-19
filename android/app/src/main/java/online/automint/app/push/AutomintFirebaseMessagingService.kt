package online.automint.app.push

import android.app.PendingIntent
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import online.automint.app.AutomintApp
import online.automint.app.MainActivity
import online.automint.app.R
import online.automint.app.settings.SettingsStore

class AutomintFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        PushTokenRegistrar.register(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!SettingsStore(this).notificationsEnabled) return

        val title = message.notification?.title ?: message.data["title"] ?: getString(R.string.app_name)
        val body = message.notification?.body ?: message.data["body"] ?: return
        val deepLink = message.data["deep_link"]

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (!deepLink.isNullOrBlank()) {
                action = Intent.ACTION_VIEW
                data = Uri.parse(deepLink)
            }
        }

        val pending = PendingIntent.getActivity(
            this,
            message.messageId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, AutomintApp.DEFAULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.ic_splash_logo))
            .setColor(ContextCompat.getColor(this, R.color.am_primary))
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        val manager = NotificationManagerCompat.from(this)
        if (manager.areNotificationsEnabled()) {
            runCatching { manager.notify(message.messageId?.hashCode() ?: 0, notification) }
        }
    }
}
