package org.archuser.mqttnotify.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import androidx.core.app.NotificationCompat
import org.archuser.mqttnotify.R
import org.archuser.mqttnotify.config.TopicConfig
import org.json.JSONObject

class NotificationHelper(private val context: Context) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    data class Payload(
        val title: String,
        val body: String,
        val priority: String?,
        val tag: String?
    )

    fun ensureChannel(topicConfig: TopicConfig) {
        val importance = when (topicConfig.qos) {
            2 -> NotificationManager.IMPORTANCE_HIGH
            1 -> NotificationManager.IMPORTANCE_DEFAULT
            else -> NotificationManager.IMPORTANCE_LOW
        }
        val channel = NotificationChannel(
            topicConfig.notificationChannelId,
            topicConfig.topic,
            importance
        )
        channel.description = context.getString(R.string.notification_channel_description)
        channel.enableVibration(topicConfig.qos == 2)
        channel.enableLights(topicConfig.qos >= 1)
        if (topicConfig.qos == 2) {
            channel.lightColor = Color.RED
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun postNotification(topicConfig: TopicConfig, mqttQos: Int, payload: String) {
        val parsed = parsePayload(payload, topicConfig.topic)
        val builder = NotificationCompat.Builder(context, topicConfig.notificationChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(parsed.title)
            .setContentText(parsed.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(parsed.body))
            .setAutoCancel(true)

        val priority = when (parsed.priority) {
            "urgent" -> NotificationCompat.PRIORITY_MAX
            "high" -> NotificationCompat.PRIORITY_HIGH
            "low" -> NotificationCompat.PRIORITY_LOW
            else -> NotificationCompat.PRIORITY_DEFAULT
        }
        val qosPriority = when (mqttQos) {
            2 -> NotificationCompat.PRIORITY_MAX
            1 -> NotificationCompat.PRIORITY_DEFAULT
            else -> NotificationCompat.PRIORITY_LOW
        }
        builder.priority = maxOf(priority, qosPriority)
        if (mqttQos == 2 || parsed.priority == "urgent") {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        }
        val notificationId = topicConfig.topic.hashCode()
        if (parsed.tag.isNullOrBlank()) {
            notificationManager.notify(notificationId, builder.build())
        } else {
            notificationManager.notify(parsed.tag, notificationId, builder.build())
        }
    }

    private fun parsePayload(payload: String, fallbackTitle: String): Payload {
        val trimmed = payload.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JSONObject(trimmed)
                val title = json.optString("title", fallbackTitle)
                val body = json.optString("body", payload)
                val priority = json.optString("priority", null)
                val tag = json.optString("tag", null)
                return Payload(title, body, priority, tag)
            } catch (_: Exception) {
                return Payload(fallbackTitle, payload, null, null)
            }
        }
        return Payload(fallbackTitle, payload, null, null)
    }
}
