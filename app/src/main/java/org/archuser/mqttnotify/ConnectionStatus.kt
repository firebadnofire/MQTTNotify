package org.archuser.mqttnotify

enum class ConnectionState {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    RETRYING
}

data class ConnectionStatus(
    val state: ConnectionState,
    val retryInSeconds: Int? = null
)
