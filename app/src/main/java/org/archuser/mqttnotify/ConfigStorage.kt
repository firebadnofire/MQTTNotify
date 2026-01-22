package org.archuser.mqttnotify

import android.content.Context
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ConfigStorage(private val context: Context) {
    private val configFile = File(context.filesDir, "config.json")
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secure_config",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun loadConfig(): AppConfig {
        if (!configFile.exists()) {
            return AppConfig(defaultServerConfig(), emptyList())
        }
        val raw = configFile.readText()
        val json = JSONObject(raw)
        val serverJson = json.optJSONObject("server") ?: JSONObject()
        val host = serverJson.optString("host", "")
        val port = serverJson.optInt("port", 8883)
        val clientId = serverJson.optString("clientId", defaultClientId())
        val clientCertAlias = serverJson.optString("clientCertAlias", "").takeIf { it.isNotBlank() }
        val topics = mutableListOf<TopicConfig>()
        val topicsArray = json.optJSONArray("topics") ?: JSONArray()
        for (index in 0 until topicsArray.length()) {
            val topic = topicsArray.optString(index).trim()
            if (topic.isNotBlank()) {
                topics.add(TopicConfig(topic))
            }
        }
        return AppConfig(
            server = ServerConfig(host, port, clientId, clientCertAlias),
            topics = topics
        )
    }

    fun saveConfig(config: AppConfig) {
        val server = JSONObject()
        server.put("host", config.server.host)
        server.put("port", config.server.port)
        server.put("clientId", config.server.clientId)
        server.put("clientCertAlias", config.server.clientCertAlias ?: "")
        val topics = JSONArray()
        config.topics.forEach { topics.put(it.topic) }
        val json = JSONObject()
        json.put("server", server)
        json.put("topics", topics)
        configFile.writeText(json.toString(2))
    }

    fun loadSecrets(): Pair<String?, String?> {
        val username = prefs.getString("username", null)
        val password = prefs.getString("password", null)
        return username to password
    }

    fun saveSecrets(username: String?, password: String?) {
        prefs.edit()
            .putString("username", username)
            .putString("password", password)
            .apply()
    }

    fun exportConfig(output: java.io.OutputStream, config: AppConfig) {
        val server = JSONObject()
        server.put("host", config.server.host)
        server.put("port", config.server.port)
        server.put("clientId", config.server.clientId)
        server.put("clientCertAlias", config.server.clientCertAlias ?: "")
        val topics = JSONArray()
        config.topics.forEach { topics.put(it.topic) }
        val json = JSONObject()
        json.put("server", server)
        json.put("topics", topics)
        output.bufferedWriter().use { it.write(json.toString(2)) }
    }

    fun importConfig(input: java.io.InputStream): AppConfig {
        val raw = input.bufferedReader().use { it.readText() }
        val json = JSONObject(raw)
        val serverJson = json.optJSONObject("server") ?: JSONObject()
        val host = serverJson.optString("host", "")
        val port = serverJson.optInt("port", 8883)
        val clientId = serverJson.optString("clientId", defaultClientId())
        val clientCertAlias = serverJson.optString("clientCertAlias", "").takeIf { it.isNotBlank() }
        val topics = mutableListOf<TopicConfig>()
        val topicsArray = json.optJSONArray("topics") ?: JSONArray()
        for (index in 0 until topicsArray.length()) {
            val topic = topicsArray.optString(index).trim()
            if (topic.isNotBlank()) {
                topics.add(TopicConfig(topic))
            }
        }
        val config = AppConfig(
            server = ServerConfig(host, port, clientId, clientCertAlias),
            topics = topics
        )
        saveConfig(config)
        return config
    }

    private fun defaultServerConfig(): ServerConfig {
        return ServerConfig(
            host = "",
            port = 8883,
            clientId = defaultClientId(),
            clientCertAlias = null
        )
    }

    private fun defaultClientId(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return "mqttnotify-${androidId ?: "client"}"
    }
}
