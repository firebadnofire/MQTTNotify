package org.archuser.mqttnotify.mqtt

import java.net.URI

object MqttConfigValidator {
    private val allowedSchemes = setOf("ssl", "mqtts")

    fun validate(config: MqttConnectionConfig): MqttConnectionConfig {
        if (config.clientId.isBlank()) {
            throw IllegalArgumentException("Client ID must not be blank.")
        }
        val uri = URI(config.brokerUri)
        val scheme = uri.scheme?.lowercase()
            ?: throw IllegalArgumentException("Broker URI must include a scheme.")
        if (scheme !in allowedSchemes) {
            throw IllegalArgumentException("Broker URI scheme must be mqtts:// or ssl://.")
        }
        if (uri.host.isNullOrBlank()) {
            throw IllegalArgumentException("Broker URI must include a host.")
        }
        if (uri.userInfo != null) {
            throw IllegalArgumentException("Broker URI must not include user info.")
        }
        return config
    }
}
