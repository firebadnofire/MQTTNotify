package org.archuser.mqttnotify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.Executors

class MqttForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val configStorage by lazy { ConfigStorage(this) }
    private var mqttClient: MqttAsyncClient? = null
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> startConnection()
            ACTION_DISCONNECT -> disconnect()
            else -> startConnection()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        disconnect()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun startConnection() {
        val config = configStorage.loadConfig()
        if (config.server.host.isBlank()) {
            ConnectionStatusRepository.update(ConnectionStatus(ConnectionState.DISCONNECTED))
            return
        }
        startForeground(NOTIFICATION_ID, buildServiceNotification("Connecting to ${config.server.host}"))
        ConnectionStatusRepository.update(ConnectionStatus(ConnectionState.CONNECTING))
        executor.execute {
            try {
                connectInternal(config)
            } catch (ex: Exception) {
                scheduleReconnect()
            }
        }
    }

    private fun connectInternal(config: AppConfig) {
        val brokerUrl = "ssl://${config.server.host}:${config.server.port}"
        if (mqttClient?.serverURI != brokerUrl) {
            mqttClient?.close()
            mqttClient = MqttAsyncClient(brokerUrl, config.server.clientId, MemoryPersistence())
        }
        val client = mqttClient ?: return
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
                ConnectionStatusRepository.update(ConnectionStatus(ConnectionState.CONNECTED))
                updateForegroundNotification("Connected to ${config.server.host}")
                subscribeTopics(config, client)
            }

            override fun connectionLost(cause: Throwable?) {
                scheduleReconnect()
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                val body = message?.payload?.toString(Charsets.UTF_8) ?: ""
                val resolvedTopic = topic ?: "unknown"
                NotificationRecorder.record(this@MqttForegroundService, resolvedTopic, body)
                val payload = NotificationParser.parse(resolvedTopic, body, message?.qos ?: 0)
                NotificationHelper.notify(this@MqttForegroundService, payload)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        val (username, password) = configStorage.loadSecrets()
        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = false
            isCleanSession = true
            keepAliveInterval = 60
            socketFactory = TlsSocketFactory.create(this@MqttForegroundService, config.server.clientCertAlias)
            if (!username.isNullOrBlank()) {
                userName = username
            }
            if (!password.isNullOrBlank()) {
                this.password = password.toCharArray()
            }
        }
        if (client.isConnected) {
            subscribeTopics(config, client)
            return
        }
        client.connect(options, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
                ConnectionStatusRepository.update(ConnectionStatus(ConnectionState.CONNECTED))
                updateForegroundNotification("Connected to ${config.server.host}")
                subscribeTopics(config, client)
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                scheduleReconnect()
            }
        })
    }

    private fun subscribeTopics(config: AppConfig, client: MqttAsyncClient) {
        if (config.topics.isEmpty()) {
            return
        }
        val topics = config.topics.map { it.topic }.toTypedArray()
        val qos = IntArray(topics.size) { 2 }
        try {
            client.subscribe(topics, qos)
        } catch (_: MqttException) {
            // Ignore, reconnect loop will retry.
        }
    }

    private fun scheduleReconnect() {
        val delaySeconds = (reconnectDelayMs / 1000).toInt()
        ConnectionStatusRepository.update(ConnectionStatus(ConnectionState.RETRYING, delaySeconds))
        updateForegroundNotification("Connection lost. Retrying in ${delaySeconds}s")
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            startConnection()
        }, reconnectDelayMs)
    }

    private fun disconnect() {
        handler.removeCallbacksAndMessages(null)
        executor.execute {
            try {
                mqttClient?.disconnect()
            } catch (_: MqttException) {
                // Ignore.
            }
        }
        ConnectionStatusRepository.update(ConnectionStatus(ConnectionState.DISCONNECTED))
        updateForegroundNotification("Disconnected")
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun buildServiceNotification(content: String): android.app.Notification {
        ensureServiceChannel()
        return NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setContentTitle("MQTTNotify")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateForegroundNotification(content: String) {
        val notification = buildServiceNotification(content)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureServiceChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            SERVICE_CHANNEL,
            "MQTT connection",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_CONNECT = "org.archuser.mqttnotify.action.CONNECT"
        const val ACTION_DISCONNECT = "org.archuser.mqttnotify.action.DISCONNECT"
        private const val SERVICE_CHANNEL = "mqttnotify_service"
        private const val NOTIFICATION_ID = 1001
        private const val INITIAL_RECONNECT_DELAY_MS = 3000L
        private const val MAX_RECONNECT_DELAY_MS = 60000L
    }
}
