package org.archuser.mqttnotify

import org.json.JSONObject

enum class NotificationPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT
}

data class NotificationPayload(
    val title: String,
    val body: String,
    val priority: NotificationPriority,
    val tag: String?
)

object NotificationParser {
    fun parse(topic: String, payload: String, qos: Int): NotificationPayload {
        val trimmed = payload.trim()
        val basePriority = qosToPriority(qos)
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JSONObject(trimmed)
                val title = json.optString("title", topic).ifBlank { topic }
                val body = json.optString("body", trimmed).ifBlank { trimmed }
                val priority = parsePriority(json.optString("priority", "")) ?: basePriority
                val tag = json.optString("tag", "").takeIf { it.isNotBlank() }
                return NotificationPayload(title, body, priority, tag)
            } catch (_: Exception) {
                // Fall through to plain text.
            }
        }
        return NotificationPayload(topic, trimmed, basePriority, null)
    }

    private fun parsePriority(raw: String): NotificationPriority? {
        return when (raw.lowercase()) {
            "low" -> NotificationPriority.LOW
            "normal" -> NotificationPriority.NORMAL
            "high" -> NotificationPriority.HIGH
            "urgent" -> NotificationPriority.URGENT
            else -> null
        }
    }

    private fun qosToPriority(qos: Int): NotificationPriority {
        return when (qos) {
            0 -> NotificationPriority.LOW
            1 -> NotificationPriority.NORMAL
            2 -> NotificationPriority.URGENT
            else -> NotificationPriority.NORMAL
        }
    }
}
