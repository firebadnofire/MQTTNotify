package org.archuser.mqttnotify.mqtt

data class MqttConnectionConfig(
    val brokerUri: String,
    val clientId: String,
    val clientKeyAlias: String?,
    val subscriptions: List<String>
)

data class TopicSubscription(
    val filter: String,
    val qos: Int
)

sealed class MqttStatus {
    data object Connecting : MqttStatus()
    data object Connected : MqttStatus()
    data class FailedRetrying(val cause: Throwable?) : MqttStatus()
    data class Retrying(val attempt: Int, val delayMs: Long, val cause: Throwable?) : MqttStatus()
    data object Disconnected : MqttStatus()
}

fun interface MqttStatusListener {
    fun onStatus(status: MqttStatus)
}

fun interface MqttMessageListener {
    fun onMessage(topic: String, payload: ByteArray, qos: Int, retained: Boolean)
}
