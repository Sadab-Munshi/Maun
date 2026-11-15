package org.maun.messenger.push

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.maun.messenger.MainActivity
import org.maun.messenger.R

/**
 * Everything Maun puts in the notification shade.
 *
 * A notification never contains a message. What it says is handed to Android
 * and can be read by other apps the user has allowed to read notifications,
 * so it stays at "a message arrived", with the contact's name only on an
 * unlocked phone.
 */
class Notifications(context: Context) {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    /** Safe to call every time the app starts. */
    fun createChannels() {
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_MESSAGES, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Messages")
                    .setDescription("Tells you that a message has arrived. Never shows the message.")
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_BACKGROUND, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Background connection")
                    .setDescription("Shown while Maun stays connected or is checking for messages.")
                    .setShowBadge(false)
                    .build(),
            ),
        )
    }

    /** Whether the user lets Maun show notifications at all. */
    val allowed: Boolean
        get() = manager.areNotificationsEnabled()

    /** Shows or updates the one notification about unread messages. [alert] makes it sound. */
    // The permission is checked just above the call; lint does not follow that.
    @SuppressLint("MissingPermission")
    fun showMessages(announcement: Announcement, alert: Boolean) {
        if (!allowed) return
        val onLockScreen = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Maun")
            .setContentText(announcement.discreet)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(announcement.title)
            .setContentText(announcement.text)
            .setNumber(announcement.total)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(onLockScreen)
            .setOnlyAlertOnce(!alert)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        try {
            manager.notify(ID_MESSAGES, notification)
        } catch (_: SecurityException) {
            // Permission was withdrawn between the check and the call.
        }
    }

    fun clearMessages() {
        manager.cancel(ID_MESSAGES)
    }

    /** The permanent notification Android requires while Maun stays connected. */
    fun connected(): Notification = quiet("Maun is connected", "Waiting for messages. Tap to open.")

    /** Shown by older Android versions while Maun fetches after a wake-up. */
    fun fetching(): Notification = quiet("Maun", "Checking for messages")

    private fun quiet(title: String, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_BACKGROUND)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp())
            .build()

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val ID_MESSAGES = 1
        const val ID_CONNECTED = 2
        const val ID_FETCH = 3

        private const val CHANNEL_MESSAGES = "messages"
        private const val CHANNEL_BACKGROUND = "background"
    }
}
