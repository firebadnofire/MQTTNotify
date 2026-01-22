package org.archuser.mqttnotify

data class TopicConfig(
    val topic: String
)

data class ServerConfig(
    val host: String,
    val port: Int,
    val clientId: String,
    val clientCertAlias: String?
)

data class AppConfig(
    val server: ServerConfig,
    val topics: List<TopicConfig>
)
