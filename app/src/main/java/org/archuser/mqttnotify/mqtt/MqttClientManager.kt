package org.archuser.mqttnotify.mqtt

import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

class MqttClientManager(
    private val tlsProvider: KeystoreTlsProvider = KeystoreTlsProvider()
) {
    private var client: MqttAsyncClient? = null
    private var subscriptions: List<TopicSubscription> = emptyList()

    fun connect(
        config: MqttConnectionConfig,
        statusListener: MqttStatusListener,
        messageListener: MqttMessageListener
    ) {
        MqttConfigValidator.validate(config)
        subscriptions = TopicSubscriptionParser.parse(config.subscriptions)

        statusListener.onStatus(MqttStatus.Connecting)
        val mqttClient = MqttAsyncClient(
            config.brokerUri,
            config.clientId,
            MemoryPersistence()
        )
        mqttClient.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                statusListener.onStatus(MqttStatus.Connected)
                if (reconnect) {
                    subscribeAll(mqttClient, statusListener)
                }
            }

            override fun connectionLost(cause: Throwable?) {
                statusListener.onStatus(MqttStatus.FailedRetrying(cause))
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                messageListener.onMessage(topic, message.payload, message.qos, message.isRetained)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })

        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = false
            isCleanSession = false
            socketFactory = tlsProvider.createSocketFactory(config.clientKeyAlias)
        }

        mqttClient.connect(options, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                subscribeAll(mqttClient, statusListener)
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                statusListener.onStatus(MqttStatus.FailedRetrying(exception))
            }
        })
        client = mqttClient
    }

    fun disconnect(statusListener: MqttStatusListener) {
        val mqttClient = client ?: return
        try {
            mqttClient.disconnect(null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    statusListener.onStatus(MqttStatus.Disconnected)
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    statusListener.onStatus(MqttStatus.FailedRetrying(exception))
                }
            })
        } catch (ex: MqttException) {
            statusListener.onStatus(MqttStatus.FailedRetrying(ex))
        }
    }

    private fun subscribeAll(mqttClient: MqttAsyncClient, statusListener: MqttStatusListener) {
        if (subscriptions.isEmpty()) {
            return
        }
        val filters = subscriptions.map { it.filter }.toTypedArray()
        val qos = subscriptions.map { it.qos }.toIntArray()
        mqttClient.subscribe(filters, qos, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) = Unit

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                statusListener.onStatus(MqttStatus.FailedRetrying(exception))
            }
        })
    }
}
