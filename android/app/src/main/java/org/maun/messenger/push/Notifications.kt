package org.maun.messenger.push

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.Person
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.maun.messenger.MainActivity
import org.maun.messenger.SoundSettings
import org.maun.messenger.webrtc.CallService
import org.maun.messenger.webrtc.CallManager
import org.maun.messenger.R

/**
 * Everything Maun puts in the notification shade.
 *
 * On an unlocked phone a message notification names the contact and quotes
 * the newest message, unless the user has turned that off in Settings. What a
 * notification says is handed to Android and can be read by other apps the
 * user has allowed to read notifications. A locked screen is only ever told
 * that a message arrived.
 */
class Notifications(context: Context) {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    /** Safe to call every time the app starts. */
    fun createChannels() {
        // From an earlier version: a silent channel for calls, replaced by one that rings.
        manager.deleteNotificationChannel("calls")
        // From an earlier version: one message channel with whatever sound
        // Android gave it. There is one for each choice now, so this one goes;
        // Android keeps whatever the user did to the new ones.
        manager.deleteNotificationChannel(LEGACY_MESSAGES)
        // The three message channels. Android fixes a channel's sound when it
        // is made and never changes it again, so the choice in Settings picks a
        // channel rather than rewriting one. Each is made once and then left
        // alone: a sound turned off in Android's own settings stays off, and
        // this app never puts it back.
        for (channel in messageChannels()) {
            if (manager.getNotificationChannelCompat(channel.id) == null) {
                manager.createNotificationChannel(channel)
            }
        }
        manager.createNotificationChannelsCompat(
            listOf(
                // The ringing itself: the phone's own ringtone, and a vibration, for as long as the call rings.
                NotificationChannelCompat.Builder(CHANNEL_RING, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Incoming calls")
                    .setDescription("Rings when someone calls while Maun is not open.")
                    .setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setVibrationEnabled(true)
                    .setVibrationPattern(longArrayOf(0, 800, 1200, 800, 1200))
                    .build(),
                // Quiet: a call that is going on has the screen already and needs no banner over it.
                NotificationChannelCompat.Builder(CHANNEL_CALL_ONGOING, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Call in progress")
                    .setDescription("Shown while a call goes on, to get back to it or hang up.")
                    .setShowBadge(false)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_BACKGROUND, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Background connection")
                    .setDescription("Shown while Maun stays connected or is checking for messages.")
                    .setShowBadge(false)
                    .build(),
            ),
        )
    }

    /**
     * The three channels a message notification can arrive on: Maun's own
     * sound, the phone's notification sound, or silence. They differ only in
     * what they are called and what they play, so that a person's choice in
     * Settings is a choice of channel, and Android's own per-channel settings
     * are respected for the one they picked.
     */
    private fun messageChannels(): List<NotificationChannelCompat> {
        val maun = NotificationChannelCompat.Builder(SoundSettings.Channel.MAUN.id, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName("Messages")
            .setDescription("Tells you that a message has arrived. Never shows the message.")
            .setSound(
                Uri.parse("android.resource://${context.packageName}/${R.raw.incoming_message_ringtone1}"),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setVibrationEnabled(true)
            .build()
        val system = NotificationChannelCompat.Builder(SoundSettings.Channel.SYSTEM.id, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName("Messages (phone sound)")
            .setDescription("Tells you that a message has arrived, with the notification sound this phone uses.")
            .setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setVibrationEnabled(true)
            .build()
        val silent = NotificationChannelCompat.Builder(SoundSettings.Channel.SILENT.id, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName("Messages (silent)")
            .setDescription("Lets you know that a message has arrived, without a sound.")
            .setSound(null, null)
            .setVibrationEnabled(false)
            .build()
        return listOf(maun, system, silent)
    }

    /**
     * Whether notifications quote the newest message. On unless the user
     * turned it off; a locked screen never shows the text either way.
     */
    var showsText: Boolean
        get() = settings.getBoolean(KEY_SHOW_TEXT, true)
        set(value) = settings.edit().putBoolean(KEY_SHOW_TEXT, value).apply()

    private val settings get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Whether the user lets Maun show notifications at all. */
    val allowed: Boolean
        get() = manager.areNotificationsEnabled()

    /** Shows or updates the one notification about unread messages. [alert] makes it sound. */
    // The permission is checked just above the call; lint does not follow that.
    @SuppressLint("MissingPermission")
    fun showMessages(announcement: Announcement, alert: Boolean) {
        if (!allowed) return
        DeliveryTrace.stepped(context, DeliveryTrace.SHOWN, "alert=$alert count=${announcement.total}")
        val channel = SoundSettings.incomingChannel(context)
        val onLockScreen = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Maun")
            .setContentText(announcement.discreet)
            .build()
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(announcement.title)
            .setContentText(announcement.text)
            .setStyle(
                if (announcement.lines.isEmpty()) {
                    NotificationCompat.BigTextStyle().bigText(announcement.text)
                } else {
                    NotificationCompat.InboxStyle().also { style -> announcement.lines.forEach(style::addLine) }
                },
            )
            .setNumber(announcement.total)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(onLockScreen)
            .setOnlyAlertOnce(!alert)
            .setAutoCancel(true)
            .setContentIntent(announcement.peer?.let { peer -> openChat(peer, announcement.message) } ?: openApp())
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

    /**
     * The notification of a call that is coming in while Maun is not on
     * screen. It is the ringing: its channel carries the phone's ringtone and
     * a vibration, and it repeats them until it is taken away. It opens the
     * call over the lock screen where Android allows it, and otherwise shows
     * at the top with Answer and Decline. [name] is shown on an unlocked
     * phone only.
     */
    fun incomingCall(name: String, video: Boolean): Notification {
        val what = if (video) "Incoming video call" else "Incoming voice call"
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val answer = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_ANSWER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val decline = PendingIntent.getService(
            context,
            3,
            CallService.hangUp(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val onLockScreen = NotificationCompat.Builder(context, CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Maun")
            .setContentText(what)
            .build()
        // Android's own look for a call that is coming in: the caller, and Answer and Decline side by side.
        val caller = Person.Builder().setName(name).setImportant(true).build()
        val notification = NotificationCompat.Builder(context, CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(name)
            .setContentText(what)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer).setIsVideo(video))
            .addPerson(caller)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(onLockScreen)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(CallManager.RING_MILLIS)
            .setContentIntent(open)
            // Lights the screen and opens the call over the lock screen.
            .setFullScreenIntent(open, true)
            .build()
        // Ring again and again until answered, declined or timed out, not once.
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        return notification
    }

    /** Shows [notification] (from [incomingCall]) without a service behind it. */
    @SuppressLint("MissingPermission")
    fun showIncomingCall(notification: Notification) {
        if (!allowed) return
        try {
            manager.notify(ID_RINGING, notification)
        } catch (_: Exception) {
            // Permission was withdrawn between the check and the call, or Android
            // would not show a call's notification without a service behind it.
        }
    }

    /**
     * Shown for the moment between a wake-up that says "a call" and the call
     * itself being fetched. Silent: the ringing follows when it is known who calls.
     */
    fun callComing(): Notification =
        NotificationCompat.Builder(context, CHANNEL_CALL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Maun")
            .setContentText("Incoming call…")
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openApp())
            .build()

    /**
     * Whether Android lets Maun open a call over the lock screen. From
     * Android 14 on that is a setting of its own, which can be off for an app
     * that was not installed from a store.
     */
    fun mayShowCallsOverLockScreen(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()

    fun clearIncomingCall() {
        manager.cancel(ID_RINGING)
    }

    /** The notification Android requires while a call goes on. Says that there is one, not with whom. */
    fun inCall(hangUp: Intent): Notification {
        val end = PendingIntent.getService(context, 4, hangUp, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Android's own look for a call in progress. It names no one: it is on the lock screen too.
        val maun = Person.Builder().setName("Maun call").setImportant(true).build()
        return NotificationCompat.Builder(context, CHANNEL_CALL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Maun call")
            .setContentText("Tap to return to the call.")
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(maun, end))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openApp())
            .build()
    }

    /** The permanent notification Android requires while Maun stays connected. */
    fun connected(): Notification = quiet("Maun is connected", "Waiting for messages. Tap to open.")

    fun clearConnected() {
        manager.cancel(ID_CONNECTED)
    }

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

    /** Opens Maun at the conversation with [peer], and at [message] in it when there is one. */
    private fun openChat(peer: String, message: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_CHAT)
            .putExtra(MainActivity.EXTRA_CHAT, peer)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (message != null) intent.putExtra(MainActivity.EXTRA_MESSAGE, message)
        return PendingIntent.getActivity(
            context,
            10,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        private const val KEY_SHOW_TEXT = "notify_text"
        const val ID_MESSAGES = 1
        const val ID_CONNECTED = 2
        const val ID_FETCH = 3
        const val ID_CALL = 4
        const val ID_RINGING = 5
        private const val CHANNEL_RING = "incoming_calls"
        private const val CHANNEL_CALL_ONGOING = "call_ongoing"

        private const val LEGACY_MESSAGES = "messages"
        private const val CHANNEL_BACKGROUND = "background"
    }
}
