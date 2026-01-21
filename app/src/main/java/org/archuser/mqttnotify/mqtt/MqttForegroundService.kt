package org.archuser.mqttnotify.mqtt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.security.KeyChain
import android.util.Log
import androidx.core.app.NotificationCompat
import org.archuser.mqttnotify.R
import org.archuser.mqttnotify.config.ConfigStorage
import org.archuser.mqttnotify.notification.NotificationHelper
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

class MqttForegroundService : Service(), MqttCallbackExtended {
    private val storage by lazy { ConfigStorage(this) }
    private var client: MqttAsyncClient? = null
    private var notificationHelper: NotificationHelper? = null

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> startConnection()
            ACTION_DISCONNECT -> stopConnection()
        }
        return START_STICKY
    }

    private fun startConnection() {
        startForeground(NOTIFICATION_ID, buildServiceNotification())
        val config = storage.load()
        val host = config.server.host.trim()
        val port = config.server.port
        if (host.isBlank() || port <= 0) {
            Log.w(TAG, "Missing host or port; cannot connect")
            stopSelf()
            return
        }
        if (client?.isConnected == true) {
            return
        }
        val brokerUri = "ssl://$host:$port"
        try {
            val mqttClient = MqttAsyncClient(brokerUri, config.server.clientId, null)
            mqttClient.setCallback(this)
            val options = MqttConnectOptions()
            options.isCleanSession = true
            options.isAutomaticReconnect = true
            options.connectionTimeout = 15
            options.keepAliveInterval = 60
            options.socketFactory = buildSslSocketFactory(config.server.clientCertAlias)
            if (config.server.username.isNotBlank()) {
                options.userName = config.server.username
            }
            if (!config.server.password.isNullOrBlank()) {
                options.password = config.server.password?.toCharArray()
            }
            client = mqttClient
            notificationHelper = NotificationHelper(this)
            mqttClient.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    subscribeTopics()
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "MQTT connect failed", exception)
                    stopSelf()
                }
            })
        } catch (ex: MqttException) {
            Log.e(TAG, "Unable to connect", ex)
            stopSelf()
        }
    }

    private fun stopConnection() {
        try {
            client?.disconnect()
        } catch (ex: MqttException) {
            Log.w(TAG, "Disconnect failed", ex)
        }
        client = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun subscribeTopics() {
        val config = storage.load()
        val mqttClient = client ?: return
        config.topics.forEach { topic ->
            notificationHelper?.ensureChannel(topic)
            try {
                mqttClient.subscribe(topic.topic, topic.qos)
            } catch (ex: MqttException) {
                Log.w(TAG, "Subscribe failed for ${topic.topic}", ex)
            }
        }
    }

    override fun connectionLost(cause: Throwable?) {
        Log.w(TAG, "Connection lost", cause)
    }

    override fun messageArrived(topic: String?, message: MqttMessage?) {
        if (topic == null || message == null) {
            return
        }
        val config = storage.load()
        val topicConfig = config.topics.find { it.topic == topic } ?: return
        val payload = message.payload?.toString(Charsets.UTF_8) ?: return
        notificationHelper?.postNotification(topicConfig, message.qos, payload)
    }

    override fun deliveryComplete(token: IMqttToken?) {
        // No-op: we only subscribe.
    }

    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
        if (reconnect) {
            subscribeTopics()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildSslSocketFactory(clientCertAlias: String?): SSLSocketFactory {
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(null as KeyStore?)
        val keyManagers = if (!clientCertAlias.isNullOrBlank()) {
            try {
                val privateKey = KeyChain.getPrivateKey(this, clientCertAlias)
                val certChain = KeyChain.getCertificateChain(this, clientCertAlias)
                if (privateKey == null || certChain == null) {
                    null
                } else {
                    val keyStore = KeyStore.getInstance("PKCS12")
                    keyStore.load(null)
                    keyStore.setKeyEntry("mqtt", privateKey, null, certChain)
                    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                    keyManagerFactory.init(keyStore, null)
                    keyManagerFactory.keyManagers
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Unable to load client certificate", ex)
                null
            }
        } else {
            null
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagers, trustManagerFactory.trustManagers, SecureRandom())
        return sslContext.socketFactory
    }

    private fun buildServiceNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = getString(R.string.service_channel_description)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_body))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_CONNECT = "org.archuser.mqttnotify.action.CONNECT"
        const val ACTION_DISCONNECT = "org.archuser.mqttnotify.action.DISCONNECT"
        private const val TAG = "MqttForegroundService"
        private const val SERVICE_CHANNEL_ID = "mqtt_service"
        private const val NOTIFICATION_ID = 1001
    }
}
