package dev.nekuz.dayboard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat

object Notifications {
    private const val CHANNEL = "reminders"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.channel_reminders),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    fun enabled(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(CHANNEL) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun needsPrompt(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun show(context: Context, task: Task, quiet: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!enabled(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val time = task.time?.let { Format.time(it, DateFormat.is24HourFormat(context)) }.orEmpty()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ForeignText.clean(task.title, Limits.MAX_TITLE))
            .setContentText(context.getString(R.string.notification_due, time))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(quiet)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        try {
            if (!quiet) manager.cancel(task.id)
            manager.notify(task.id, notification)
        } catch (e: RuntimeException) {
            return
        }
    }

    fun cancel(context: Context, ids: Set<Int>) {
        if (ids.isEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ids.forEach { manager.cancel(it) }
    }
}
