package com.iodvd.fuqp.ui.util

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.iodvd.fuqp.R
import com.iodvd.fuqp.service.ConfigManager

/**
 * Posts a single, self-replacing notification summarising the result of a one-tap Auto-protect
 * run, so the user can see what it did (and which apps were newly detected) from the shade.
 *
 * Posting is a no-op when notifications are disabled or `POST_NOTIFICATIONS` (API 33+) was not
 * granted — the caller is responsible for requesting it; the in-app toast still reports the result.
 */
object AutoProtectNotifier {
    private const val CHANNEL_ID = "auto_protect"

    // Stable id: a later run replaces the previous summary instead of stacking duplicates.
    private const val NOTIFICATION_ID = 0x5A01

    fun notifyResult(context: Context, result: ConfigManager.AutoProtectResult) {
        val summary = context.getString(R.string.auto_protect_result, result.sensitive, result.proxy)
        val builder = baseBuilder(context, summary)
        if (result.newlyProtected.isNotEmpty()) {
            val header = context.resources.getQuantityString(
                R.plurals.auto_protect_notif_new,
                result.newlyProtected.size,
                result.newlyProtected.size,
            )
            val body = buildString {
                append(summary)
                append("\n\n")
                append(header)
                result.newlyProtected.sorted().forEach { append("\n• ").append(it) }
            }
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }
        post(context, builder)
    }

    fun notifyFailure(context: Context) {
        post(context, baseBuilder(context, context.getString(R.string.auto_protect_failed)))
    }

    private fun baseBuilder(context: Context, text: CharSequence): NotificationCompat.Builder {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.outline_shield_24)
            .setContentTitle(context.getString(R.string.title_auto_protect))
            .setContentText(text)
            .setAutoCancel(true)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.auto_protect_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.auto_protect_channel_desc)
            },
        )
    }

    // The caller gates on POST_NOTIFICATIONS; areNotificationsEnabled() covers the rest, and
    // notify() is itself a documented no-op without the permission.
    @SuppressLint("MissingPermission")
    private fun post(context: Context, builder: NotificationCompat.Builder) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        manager.notify(NOTIFICATION_ID, builder.build())
    }
}
