package org.archuser.mqttnotify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import kotlin.math.abs

object NotificationHelper {
    private const val CHANNEL_LOW = "mqttnotify_low"
    private const val CHANNEL_NORMAL = "mqttnotify_normal"
    private const val CHANNEL_HIGH = "mqttnotify_high"
    private const val CHANNEL_URGENT = "mqttnotify_urgent"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channels = listOf(
            NotificationChannel(CHANNEL_LOW, "MQTT Low", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_NORMAL, "MQTT Normal", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_HIGH, "MQTT High", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_URGENT, "MQTT Urgent", NotificationManager.IMPORTANCE_HIGH)
        )
        channels.forEach { channel ->
            channel.enableVibration(channel.id == CHANNEL_URGENT)
            manager.createNotificationChannel(channel)
        }
    }

    fun notify(context: Context, payload: NotificationPayload) {
        ensureChannels(context)
        val channelId = when (payload.priority) {
            NotificationPriority.LOW -> CHANNEL_LOW
            NotificationPriority.NORMAL -> CHANNEL_NORMAL
            NotificationPriority.HIGH -> CHANNEL_HIGH
            NotificationPriority.URGENT -> CHANNEL_URGENT
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setPriority(
                when (payload.priority) {
                    NotificationPriority.LOW -> NotificationCompat.PRIORITY_LOW
                    NotificationPriority.NORMAL -> NotificationCompat.PRIORITY_DEFAULT
                    NotificationPriority.HIGH -> NotificationCompat.PRIORITY_HIGH
                    NotificationPriority.URGENT -> NotificationCompat.PRIORITY_MAX
                }
            )
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = abs(payload.title.hashCode())
        val tag = payload.tag
        if (tag != null) {
            manager.notify(tag, id, notification)
        } else {
            manager.notify(id, notification)
        }
    }
}
