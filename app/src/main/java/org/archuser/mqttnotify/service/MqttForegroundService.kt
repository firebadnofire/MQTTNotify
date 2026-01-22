package org.archuser.mqttnotify.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import org.archuser.mqttnotify.R
import org.archuser.mqttnotify.mqtt.MqttClientManager
import org.archuser.mqttnotify.mqtt.MqttConnectionConfig
import org.archuser.mqttnotify.mqtt.MqttMessageListener
import org.archuser.mqttnotify.mqtt.MqttStatus
import org.archuser.mqttnotify.mqtt.MqttStatusListener
import org.archuser.mqttnotify.mqtt.MqttStatusRepository

class MqttForegroundService : Service() {
    private val clientManager = MqttClientManager()
    private val handler = Handler(Looper.getMainLooper())
    private val notificationId = AtomicInteger(MESSAGE_NOTIFICATION_ID_START)
    private val statusListener = MqttStatusListener { status -> handleStatusUpdate(status) }
    private val messageListener = MqttMessageListener { topic, payload, qos, _ ->
        handleMessage(topic, payload, qos)
    }
    private var currentConfig: MqttConnectionConfig? = null
    private var reconnectAttempts = 0
    private var reconnectRunnable: Runnable? = null
    private var isStopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = intent?.toMqttConnectionConfig()
        if (config == null) {
            updateStatus(MqttStatus.Disconnected)
            stopSelf()
            return START_NOT_STICKY
        }
        if (currentConfig != null && currentConfig != config) {
            clientManager.disconnect(statusListener)
        }
        currentConfig = config
        reconnectAttempts = 0
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
        startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification(MqttStatus.Connecting))
        connect()
        return START_STICKY
    }

    override fun onDestroy() {
        isStopping = true
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        clientManager.disconnect(statusListener)
        super.onDestroy()
    }

    private fun connect() {
        val config = currentConfig ?: return
        updateStatus(MqttStatus.Connecting)
        clientManager.connect(config, statusListener, messageListener)
    }

    private fun handleStatusUpdate(status: MqttStatus) {
        when (status) {
            is MqttStatus.Connecting -> updateStatus(status)
            is MqttStatus.Connected -> {
                reconnectAttempts = 0
                reconnectRunnable?.let { handler.removeCallbacks(it) }
                reconnectRunnable = null
                updateStatus(status)
            }
            is MqttStatus.FailedRetrying -> scheduleReconnect(status.cause)
            is MqttStatus.Retrying -> updateStatus(status)
            is MqttStatus.Disconnected -> updateStatus(status)
        }
    }

    private fun scheduleReconnect(cause: Throwable?) {
        if (isStopping) {
            return
        }
        reconnectAttempts += 1
        val boundedAttempt = (reconnectAttempts - 1).coerceAtMost(RETRY_MAX_SHIFT)
        val delayMs = min(
            RETRY_BASE_DELAY_MS * (1L shl boundedAttempt),
            RETRY_MAX_DELAY_MS
        )
        val status = MqttStatus.Retrying(reconnectAttempts, delayMs, cause)
        updateStatus(status)
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = Runnable { connect() }
        handler.postDelayed(reconnectRunnable!!, delayMs)
    }

    private fun updateStatus(status: MqttStatus) {
        MqttStatusRepository.update(status)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification(status))
    }

    private fun handleMessage(topic: String, payload: ByteArray, qos: Int) {
        val parsed = MqttNotificationPayloadParser.parse(payload, topic, qos)
        postNotification(parsed)
    }

    private fun postNotification(payload: MqttNotificationPayload) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = channelIdForPriority(payload.priority)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
            .setAutoCancel(true)
            .build()
        val tag = payload.tag
        if (tag.isNullOrBlank()) {
            manager.notify(notificationId.getAndIncrement(), notification)
        } else {
            manager.notify(tag, MESSAGE_TAG_NOTIFICATION_ID, notification)
        }
    }

    private fun buildForegroundNotification(status: MqttStatus): Notification {
        val statusText = when (status) {
            MqttStatus.Connecting -> getString(R.string.mqtt_status_connecting)
            MqttStatus.Connected -> getString(R.string.mqtt_status_connected)
            is MqttStatus.Retrying -> getString(
                R.string.mqtt_status_retrying,
                status.attempt,
                status.delayMs / 1000
            )
            MqttStatus.Disconnected -> getString(R.string.mqtt_status_disconnected)
            is MqttStatus.FailedRetrying -> getString(R.string.mqtt_status_retrying_generic)
        }
        return NotificationCompat.Builder(this, getString(R.string.foreground_service_channel_id))
            .setContentTitle(getString(R.string.foreground_service_notification_title))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    private fun channelIdForPriority(priority: NotificationPriority): String = when (priority) {
        NotificationPriority.LOW -> getString(R.string.notification_channel_low_id)
        NotificationPriority.NORMAL -> getString(R.string.notification_channel_normal_id)
        NotificationPriority.HIGH -> getString(R.string.notification_channel_high_id)
        NotificationPriority.URGENT -> getString(R.string.notification_channel_urgent_id)
    }

    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val foregroundChannel = NotificationChannel(
                getString(R.string.foreground_service_channel_id),
                getString(R.string.foreground_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.foreground_service_channel_description)
            }
            manager.createNotificationChannel(foregroundChannel)
            manager.createNotificationChannel(
                NotificationChannel(
                    getString(R.string.notification_channel_low_id),
                    getString(R.string.notification_channel_low_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notification_channel_low_description)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    getString(R.string.notification_channel_normal_id),
                    getString(R.string.notification_channel_normal_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = getString(R.string.notification_channel_normal_description)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    getString(R.string.notification_channel_high_id),
                    getString(R.string.notification_channel_high_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = getString(R.string.notification_channel_high_description)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    getString(R.string.notification_channel_urgent_id),
                    getString(R.string.notification_channel_urgent_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = getString(R.string.notification_channel_urgent_description)
                    enableVibration(true)
                }
            )
        }
    }

    companion object {
        const val EXTRA_BROKER_URI = "org.archuser.mqttnotify.extra.BROKER_URI"
        const val EXTRA_CLIENT_ID = "org.archuser.mqttnotify.extra.CLIENT_ID"
        const val EXTRA_CLIENT_KEY_ALIAS = "org.archuser.mqttnotify.extra.CLIENT_KEY_ALIAS"
        const val EXTRA_SUBSCRIPTIONS = "org.archuser.mqttnotify.extra.SUBSCRIPTIONS"

        private const val FOREGROUND_NOTIFICATION_ID = 1
        private const val MESSAGE_NOTIFICATION_ID_START = 1000
        private const val MESSAGE_TAG_NOTIFICATION_ID = 1
        private const val RETRY_BASE_DELAY_MS = 1_000L
        private const val RETRY_MAX_DELAY_MS = 60_000L
        private const val RETRY_MAX_SHIFT = 6
    }
}

private fun Intent.toMqttConnectionConfig(): MqttConnectionConfig? {
    val brokerUri = getStringExtra(MqttForegroundService.EXTRA_BROKER_URI)
    val clientId = getStringExtra(MqttForegroundService.EXTRA_CLIENT_ID)
    val subscriptions = getStringArrayListExtra(MqttForegroundService.EXTRA_SUBSCRIPTIONS)
    if (brokerUri.isNullOrBlank() || clientId.isNullOrBlank() || subscriptions.isNullOrEmpty()) {
        return null
    }
    return MqttConnectionConfig(
        brokerUri = brokerUri,
        clientId = clientId,
        clientKeyAlias = getStringExtra(MqttForegroundService.EXTRA_CLIENT_KEY_ALIAS),
        subscriptions = subscriptions
    )
}
