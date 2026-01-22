package org.archuser.mqttnotify.service

import org.json.JSONException
import org.json.JSONObject

data class MqttNotificationPayload(
    val title: String,
    val body: String,
    val priority: NotificationPriority,
    val tag: String?
)

enum class NotificationPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT
}

object MqttNotificationPayloadParser {
    fun parse(payload: ByteArray, topic: String, qos: Int): MqttNotificationPayload {
        val text = payload.toString(Charsets.UTF_8).trim()
        val qosPriority = NotificationPriorityMapper.fromQos(qos)
        if (text.startsWith("{")) {
            try {
                val json = JSONObject(text)
                val title = json.optString("title").ifBlank { topic }
                val body = json.optString("body")
                val priorityValue = json.optString("priority")
                val priority = NotificationPriorityMapper.fromString(priorityValue) ?: qosPriority
                val tag = json.optString("tag").ifBlank { null }
                return MqttNotificationPayload(
                    title = title,
                    body = body,
                    priority = priority,
                    tag = tag
                )
            } catch (_: JSONException) {
                // Fall back to plain text parsing.
            }
        }
        return MqttNotificationPayload(
            title = topic,
            body = text,
            priority = qosPriority,
            tag = null
        )
    }
}

object NotificationPriorityMapper {
    fun fromQos(qos: Int): NotificationPriority = when (qos) {
        0 -> NotificationPriority.LOW
        1 -> NotificationPriority.NORMAL
        2 -> NotificationPriority.URGENT
        else -> NotificationPriority.NORMAL
    }

    fun fromString(value: String?): NotificationPriority? = when (value?.lowercase()) {
        "low" -> NotificationPriority.LOW
        "normal" -> NotificationPriority.NORMAL
        "high" -> NotificationPriority.HIGH
        "urgent" -> NotificationPriority.URGENT
        else -> null
    }
}
