package org.archuser.mqttnotify

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PreferencesStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun loadConfig(): MqttConfig {
        return MqttConfig(
            brokerUri = prefs.getString(KEY_BROKER_URI, "") ?: "",
            clientId = prefs.getString(KEY_CLIENT_ID, "") ?: "",
            username = prefs.getString(KEY_USERNAME, "") ?: "",
            password = prefs.getString(KEY_PASSWORD, "") ?: "",
            topics = prefs.getString(KEY_TOPICS, "")
                ?.lines()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList(),
            clientCertAlias = prefs.getString(KEY_CLIENT_CERT_ALIAS, null)
        )
    }

    fun loadServiceStatus(): String? {
        return prefs.getString(KEY_SERVICE_STATUS, null)
    }

    fun saveServiceStatus(status: String) {
        prefs.edit {
            putString(KEY_SERVICE_STATUS, status)
        }
    }

    fun updateConfig(update: (MqttConfig) -> MqttConfig): MqttConfig {
        val updated = update(loadConfig())
        prefs.edit {
            putString(KEY_BROKER_URI, updated.brokerUri)
            putString(KEY_CLIENT_ID, updated.clientId)
            putString(KEY_USERNAME, updated.username)
            putString(KEY_PASSWORD, updated.password)
            putString(KEY_TOPICS, updated.topics.joinToString("\n"))
            putString(KEY_CLIENT_CERT_ALIAS, updated.clientCertAlias)
        }
        return updated
    }

    companion object {
        private const val PREFS_NAME = "mqtt_notify_prefs"
        private const val KEY_BROKER_URI = "broker_uri"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_TOPICS = "topics"
        private const val KEY_CLIENT_CERT_ALIAS = "client_cert_alias"
        private const val KEY_SERVICE_STATUS = "service_status"
    }
}
