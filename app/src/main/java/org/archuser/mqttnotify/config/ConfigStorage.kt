package org.archuser.mqttnotify.config

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

class ConfigStorage(private val context: Context) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "mqttnotify_config",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun load(): AppConfig {
        val configJson = prefs.getString(KEY_CONFIG_JSON, null)
        val password = prefs.getString(KEY_PASSWORD, null)
        val defaultServer = ServerConfig(
            host = "",
            port = 8883,
            clientId = "mqttnotify-${System.currentTimeMillis()}",
            username = "",
            password = password,
            clientCertAlias = null
        )
        if (configJson.isNullOrBlank()) {
            return AppConfig(defaultServer, emptyList())
        }
        val json = JSONObject(configJson)
        val serverJson = json.optJSONObject("server") ?: JSONObject()
        val topicsJson = json.optJSONArray("topics") ?: JSONArray()
        val server = ServerConfig(
            host = serverJson.optString("host", ""),
            port = serverJson.optInt("port", 8883),
            clientId = serverJson.optString("clientId", defaultServer.clientId),
            username = serverJson.optString("username", ""),
            password = password,
            clientCertAlias = serverJson.optString("clientCertAlias", null)
        )
        val topics = mutableListOf<TopicConfig>()
        for (index in 0 until topicsJson.length()) {
            val topicJson = topicsJson.optJSONObject(index) ?: continue
            val topic = topicJson.optString("topic", "")
            if (topic.isBlank()) {
                continue
            }
            val qos = topicJson.optInt("qos", 0)
            topics.add(TopicConfig(topic, qos))
        }
        return AppConfig(server, topics)
    }

    fun save(config: AppConfig) {
        val json = JSONObject()
        val serverJson = JSONObject()
        serverJson.put("host", config.server.host)
        serverJson.put("port", config.server.port)
        serverJson.put("clientId", config.server.clientId)
        serverJson.put("username", config.server.username)
        serverJson.put("clientCertAlias", config.server.clientCertAlias)
        json.put("server", serverJson)
        val topicsJson = JSONArray()
        config.topics.forEach { topic ->
            val topicJson = JSONObject()
            topicJson.put("topic", topic.topic)
            topicJson.put("qos", topic.qos)
            topicsJson.put(topicJson)
        }
        json.put("topics", topicsJson)
        prefs.edit()
            .putString(KEY_CONFIG_JSON, json.toString())
            .putString(KEY_PASSWORD, config.server.password)
            .apply()
    }

    fun exportConfig(config: AppConfig): String {
        val json = JSONObject()
        val serverJson = JSONObject()
        serverJson.put("host", config.server.host)
        serverJson.put("port", config.server.port)
        serverJson.put("clientId", config.server.clientId)
        serverJson.put("username", config.server.username)
        serverJson.put("clientCertAlias", config.server.clientCertAlias)
        json.put("server", serverJson)
        val topicsJson = JSONArray()
        config.topics.forEach { topic ->
            val topicJson = JSONObject()
            topicJson.put("topic", topic.topic)
            topicJson.put("qos", topic.qos)
            topicsJson.put(topicJson)
        }
        json.put("topics", topicsJson)
        return json.toString(2)
    }

    fun importConfig(raw: String, existingPassword: String?): AppConfig {
        val json = JSONObject(raw)
        val serverJson = json.optJSONObject("server") ?: JSONObject()
        val topicsJson = json.optJSONArray("topics") ?: JSONArray()
        val server = ServerConfig(
            host = serverJson.optString("host", ""),
            port = serverJson.optInt("port", 8883),
            clientId = serverJson.optString("clientId", "mqttnotify-${System.currentTimeMillis()}"),
            username = serverJson.optString("username", ""),
            password = existingPassword,
            clientCertAlias = serverJson.optString("clientCertAlias", null)
        )
        val topics = mutableListOf<TopicConfig>()
        for (index in 0 until topicsJson.length()) {
            val topicJson = topicsJson.optJSONObject(index) ?: continue
            val topic = topicJson.optString("topic", "")
            if (topic.isBlank()) {
                continue
            }
            val qos = topicJson.optInt("qos", 0)
            topics.add(TopicConfig(topic, qos))
        }
        return AppConfig(server, topics)
    }

    companion object {
        private const val KEY_CONFIG_JSON = "config_json"
        private const val KEY_PASSWORD = "password"
    }
}
