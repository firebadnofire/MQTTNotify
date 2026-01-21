package org.archuser.mqttnotify.config

data class TopicConfig(
    val topic: String,
    val qos: Int,
    val notificationChannelId: String = "topic_${topic.hashCode()}"
)

data class ServerConfig(
    val host: String,
    val port: Int,
    val clientId: String,
    val username: String,
    val password: String?,
    val clientCertAlias: String?
)

data class AppConfig(
    val server: ServerConfig,
    val topics: List<TopicConfig>
)
