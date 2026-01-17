package org.archuser.mqttnotify

data class MqttConfig(
    val brokerUri: String,
    val clientId: String,
    val username: String,
    val password: String,
    val topics: List<String>,
    val clientCertAlias: String?
)
