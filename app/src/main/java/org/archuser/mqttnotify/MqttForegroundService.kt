package org.archuser.mqttnotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

class MqttForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var prefs: PreferencesStore
    private var mqttClient: MqttAsyncClient? = null
    private var reconnectJob: Job? = null
    private var currentStatus = Status.DISCONNECTED
    private var backoffMs = INITIAL_BACKOFF_MS

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesStore(this)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startConnection()
            ACTION_STOP -> stopConnection()
        }
        return START_STICKY
    }

    private fun startConnection() {
        updateStatus(Status.CONNECTING)
        startForeground(NOTIFICATION_ID, buildServiceNotification())
        reconnectJob?.cancel()
        scope.launch {
            connectAndSubscribe()
        }
    }

    private suspend fun connectAndSubscribe() {
        val config = prefs.loadConfig()
        if (!config.brokerUri.startsWith("ssl://") || config.topics.isEmpty()) {
            updateStatus(Status.MISCONFIGURED)
            return
        }

        try {
            mqttClient?.disconnectForcibly()
        } catch (_: Exception) {
        }

        val clientId = if (config.clientId.isNotBlank()) config.clientId else MqttAsyncClient.generateClientId()
        val client = MqttAsyncClient(config.brokerUri, clientId, MemoryPersistence())
        mqttClient = client

        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                updateStatus(Status.CONNECTED)
            }

            override fun connectionLost(cause: Throwable?) {
                updateStatus(Status.DISCONNECTED)
                scheduleReconnect()
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                handleMessage(topic, message)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })

        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = false
            isCleanSession = true
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
            keepAliveInterval = calculateKeepAliveSeconds()
            if (config.username.isNotBlank()) {
                userName = config.username
            }
            if (config.password.isNotBlank()) {
                password = config.password.toCharArray()
            }
            socketFactory = MqttTlsSocketFactory.create(this@MqttForegroundService, config.clientCertAlias)
        }

        try {
            val token = client.connect(options)
            token.waitForCompletion()
            subscribeToTopics(client, config)
            backoffMs = INITIAL_BACKOFF_MS
            updateStatus(Status.CONNECTED)
        } catch (_: Exception) {
            updateStatus(Status.DISCONNECTED)
            scheduleReconnect()
        }
    }

    private fun subscribeToTopics(client: MqttAsyncClient, config: MqttConfig) {
        val parsed = config.topics.map { topicLine ->
            val parts = topicLine.split(":", limit = 2)
            val topic = parts[0].trim()
            val qos = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 2) ?: DEFAULT_QOS
            topic to qos
        }.filter { it.first.isNotEmpty() }

        if (parsed.isEmpty()) {
            updateStatus(Status.MISCONFIGURED)
            return
        }

        val topics = parsed.map { it.first }.toTypedArray()
        val qosValues = parsed.map { it.second }.toIntArray()
        client.subscribe(topics, qosValues, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                updateStatus(Status.CONNECTED)
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                updateStatus(Status.DISCONNECTED)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            connectAndSubscribe()
        }
    }

    private fun stopConnection() {
        reconnectJob?.cancel()
        scope.launch {
            try {
                mqttClient?.disconnectForcibly()
            } catch (_: Exception) {
            }
            mqttClient = null
            updateStatus(Status.DISCONNECTED)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateStatus(status: Status) {
        currentStatus = status
        prefs.saveServiceStatus(status.name)
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildServiceNotification())
    }

    private fun buildServiceNotification(): Notification {
        val statusText = when (currentStatus) {
            Status.CONNECTING -> getString(R.string.service_notification_connecting)
            Status.CONNECTED -> getString(R.string.service_notification_connected)
            Status.MISCONFIGURED -> getString(R.string.service_notification_misconfigured)
            Status.DISCONNECTED -> getString(R.string.service_notification_disconnected)
        }

        return NotificationCompat.Builder(this, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun handleMessage(topic: String, message: MqttMessage) {
        val payload = String(message.payload, Charsets.UTF_8)
        val parsed = MessagePayloadParser.parse(payload)
        val priority = parsed.priority ?: mapQosToPriority(message.qos)

        val channelId = when (priority) {
            MessagePriority.LOW -> CHANNEL_MESSAGES_LOW
            MessagePriority.NORMAL -> CHANNEL_MESSAGES_NORMAL
            MessagePriority.HIGH -> CHANNEL_MESSAGES_HIGH
            MessagePriority.URGENT -> CHANNEL_MESSAGES_URGENT
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(parsed.title ?: topic)
            .setContentText(parsed.body ?: payload)
            .setStyle(NotificationCompat.BigTextStyle().bigText(parsed.body ?: payload))
            .setPriority(priority.toCompatPriority())
            .setAutoCancel(true)
            .build()

        val tag = parsed.tag
        val manager = NotificationManagerCompat.from(this)
        if (tag != null) {
            manager.notify(tag, MESSAGE_NOTIFICATION_ID, notification)
        } else {
            manager.notify(MESSAGE_NOTIFICATION_ID, notification)
        }
    }

    private fun mapQosToPriority(qos: Int): MessagePriority {
        return when (qos) {
            0 -> MessagePriority.LOW
            2 -> MessagePriority.URGENT
            else -> MessagePriority.NORMAL
        }
    }

    private fun calculateKeepAliveSeconds(): Int {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return DEFAULT_KEEPALIVE_SECONDS
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return DEFAULT_KEEPALIVE_SECONDS
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> WIFI_KEEPALIVE_SECONDS
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> CELLULAR_KEEPALIVE_SECONDS
            else -> DEFAULT_KEEPALIVE_SECONDS
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java)

        val connectionChannel = NotificationChannel(
            CHANNEL_CONNECTION,
            getString(R.string.service_notification_title),
            NotificationManager.IMPORTANCE_LOW
        )

        val lowChannel = NotificationChannel(
            CHANNEL_MESSAGES_LOW,
            getString(R.string.message_channel_low),
            NotificationManager.IMPORTANCE_LOW
        )

        val normalChannel = NotificationChannel(
            CHANNEL_MESSAGES_NORMAL,
            getString(R.string.message_channel_normal),
            NotificationManager.IMPORTANCE_DEFAULT
        )

        val highChannel = NotificationChannel(
            CHANNEL_MESSAGES_HIGH,
            getString(R.string.message_channel_high),
            NotificationManager.IMPORTANCE_HIGH
        )

        val urgentChannel = NotificationChannel(
            CHANNEL_MESSAGES_URGENT,
            getString(R.string.message_channel_urgent),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
        }

        manager.createNotificationChannel(connectionChannel)
        manager.createNotificationChannel(lowChannel)
        manager.createNotificationChannel(normalChannel)
        manager.createNotificationChannel(highChannel)
        manager.createNotificationChannel(urgentChannel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceJob.cancel()
        try {
            mqttClient?.disconnectForcibly()
        } catch (_: Exception) {
        }
        mqttClient = null
        super.onDestroy()
    }

    private enum class Status {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        MISCONFIGURED
    }

    companion object {
        const val ACTION_START = "org.archuser.mqttnotify.action.START"
        const val ACTION_STOP = "org.archuser.mqttnotify.action.STOP"

        private const val CHANNEL_CONNECTION = "mqtt_connection"
        private const val CHANNEL_MESSAGES_LOW = "mqtt_messages_low"
        private const val CHANNEL_MESSAGES_NORMAL = "mqtt_messages_normal"
        private const val CHANNEL_MESSAGES_HIGH = "mqtt_messages_high"
        private const val CHANNEL_MESSAGES_URGENT = "mqtt_messages_urgent"

        private const val NOTIFICATION_ID = 100
        private const val MESSAGE_NOTIFICATION_ID = 200

        private const val DEFAULT_KEEPALIVE_SECONDS = 120
        private const val WIFI_KEEPALIVE_SECONDS = 60
        private const val CELLULAR_KEEPALIVE_SECONDS = 180

        private const val INITIAL_BACKOFF_MS = 2000L
        private const val MAX_BACKOFF_MS = 60000L
        private const val DEFAULT_QOS = 1
    }
}

private object MqttTlsSocketFactory {

    fun create(context: Context, clientCertAlias: String?): javax.net.ssl.SSLSocketFactory {
        if (clientCertAlias.isNullOrBlank()) {
            return SSLContext.getInstance("TLS").apply {
                init(null, null, null)
            }.socketFactory
        }

        val privateKey = android.security.KeyChain.getPrivateKey(context, clientCertAlias)
        val certificateChain = android.security.KeyChain.getCertificateChain(context, clientCertAlias)
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("mqtt", privateKey, null, certificateChain)
        }

        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, null)

        return SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, null, null)
        }.socketFactory
    }
}

private data class ParsedMessage(
    val title: String?,
    val body: String?,
    val priority: MessagePriority?,
    val tag: String?
)

private enum class MessagePriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT
}

private fun MessagePriority.toCompatPriority(): Int {
    return when (this) {
        MessagePriority.LOW -> NotificationCompat.PRIORITY_LOW
        MessagePriority.NORMAL -> NotificationCompat.PRIORITY_DEFAULT
        MessagePriority.HIGH -> NotificationCompat.PRIORITY_HIGH
        MessagePriority.URGENT -> NotificationCompat.PRIORITY_MAX
    }
}

private object MessagePayloadParser {

    fun parse(payload: String): ParsedMessage {
        val trimmed = payload.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return try {
                val json = JSONObject(trimmed)
                ParsedMessage(
                    title = json.optString("title").takeIf { it.isNotBlank() },
                    body = json.optString("body").takeIf { it.isNotBlank() },
                    priority = json.optString("priority").ifBlank { null }?.toPriority(),
                    tag = json.optString("tag").takeIf { it.isNotBlank() }
                )
            } catch (_: Exception) {
                ParsedMessage(null, null, null, null)
            }
        }
        return ParsedMessage(null, null, null, null)
    }

    private fun String.toPriority(): MessagePriority? {
        return when (lowercase()) {
            "low" -> MessagePriority.LOW
            "normal" -> MessagePriority.NORMAL
            "high" -> MessagePriority.HIGH
            "urgent" -> MessagePriority.URGENT
            else -> null
        }
    }
}
