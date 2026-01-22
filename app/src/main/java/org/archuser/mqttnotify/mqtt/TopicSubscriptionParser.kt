package org.archuser.mqttnotify.mqtt

object TopicSubscriptionParser {
    private const val QOS_SEPARATOR = ':'

    fun parse(rawSubscriptions: List<String>): List<TopicSubscription> {
        if (rawSubscriptions.isEmpty()) {
            throw IllegalArgumentException("At least one topic subscription is required.")
        }
        return rawSubscriptions.mapIndexed { index, raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                throw IllegalArgumentException("Subscription at index $index is blank.")
            }
            val (filter, qos) = parseFilterAndQos(trimmed, index)
            validateTopicFilter(filter, index)
            TopicSubscription(filter, qos)
        }
    }

    private fun parseFilterAndQos(raw: String, index: Int): Pair<String, Int> {
        val separatorIndex = raw.lastIndexOf(QOS_SEPARATOR)
        if (separatorIndex <= 0 || separatorIndex == raw.length - 1) {
            return raw to 1
        }
        val filter = raw.substring(0, separatorIndex)
        val qosPart = raw.substring(separatorIndex + 1)
        val qos = qosPart.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid QoS '$qosPart' at index $index.")
        if (qos !in 0..2) {
            throw IllegalArgumentException("QoS must be 0, 1, or 2 at index $index.")
        }
        if (filter.contains(QOS_SEPARATOR)) {
            throw IllegalArgumentException("Topic filter cannot contain ':' when specifying QoS at index $index.")
        }
        return filter to qos
    }

    private fun validateTopicFilter(filter: String, index: Int) {
        if (filter.contains('\u0000')) {
            throw IllegalArgumentException("Topic filter contains null character at index $index.")
        }
        if (filter.startsWith('/') || filter.endsWith('/')) {
            throw IllegalArgumentException("Topic filter must not start or end with '/' at index $index.")
        }
        if (filter.contains("//")) {
            throw IllegalArgumentException("Topic filter contains empty level at index $index.")
        }
        val levels = filter.split('/')
        levels.forEachIndexed { levelIndex, level ->
            when {
                level == "+" -> Unit
                level == "#" -> {
                    if (levelIndex != levels.lastIndex) {
                        throw IllegalArgumentException("Multi-level wildcard must be last at index $index.")
                    }
                }
                level.contains('+') -> throw IllegalArgumentException(
                    "Single-level wildcard must occupy entire level at index $index."
                )
                level.contains('#') -> throw IllegalArgumentException(
                    "Multi-level wildcard must occupy entire level at index $index."
                )
            }
        }
    }
}
