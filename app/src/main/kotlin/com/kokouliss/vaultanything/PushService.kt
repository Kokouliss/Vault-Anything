package com.kokouliss.vaultanything

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.kokouliss.vaultanything.core.ui.R as CoreR

/**
 * Shows a reminder from firebase/functions that arrives while the app is open. When the app is
 * closed the system shows it by itself and this is never called; without this, an open app drops it.
 */
// shortcut: no onNewToken, so a renewed address reaches the server at the next app start (the app
// registers on every start). Override it if reminders go missing until the app is opened.
class PushService : FirebaseMessagingService() {

    @SuppressLint("MissingPermission") // Returns early when notifications are off.
    override fun onMessageReceived(message: RemoteMessage) {
        val push = message.notification ?: return
        val notifications = NotificationManagerCompat.from(this)
        if (!notifications.areNotificationsEnabled()) return

        // The same words the system would show: the string the server names, in this phone's language.
        val text = pushText(push.bodyLocalizationKey)?.let { getString(it) } ?: push.body
        // Opens the document, like a tap on a reminder the system showed.
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_SPACE_ID, message.data[EXTRA_SPACE_ID])
            .putExtra(EXTRA_DOCUMENT_ID, message.data[EXTRA_DOCUMENT_ID])
        val tap = PendingIntent.getActivity(
            this,
            push.tag.hashCode(),
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, push.channelId ?: REMINDERS_CHANNEL)
            .setSmallIcon(CoreR.drawable.core_ui_ic_event)
            .setContentTitle(push.title)
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        // The server's tag, so a newer reminder about the same document replaces the older one.
        notifications.notify(push.tag, 0, notification)
    }
}

/** The app string that remindExpiring in firebase/functions names, or null for one this app does not know. */
internal fun pushText(key: String?): Int? = when (key) {
    "push_expires_month" -> R.string.push_expires_month
    "push_expires_week" -> R.string.push_expires_week
    "push_expires_today" -> R.string.push_expires_today
    else -> null
}
