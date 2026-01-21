package org.archuser.mqttnotify

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.security.KeyChain
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import org.archuser.mqttnotify.config.AppConfig
import org.archuser.mqttnotify.config.ConfigStorage
import org.archuser.mqttnotify.config.ServerConfig
import org.archuser.mqttnotify.config.TopicConfig
import org.archuser.mqttnotify.databinding.ActivityMainBinding
import org.archuser.mqttnotify.databinding.DialogTopicBinding
import org.archuser.mqttnotify.mqtt.MqttForegroundService
import org.archuser.mqttnotify.ui.TopicAdapter

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: ConfigStorage
    private val topics = mutableListOf<TopicConfig>()
    private lateinit var adapter: TopicAdapter
    private var currentConfig: AppConfig? = null

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            exportConfig(uri)
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importConfig(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = ConfigStorage(this)
        setSupportActionBar(binding.toolbar)
        setupDrawer()
        setupTopics()
        setupServerForm()
        requestNotificationPermission()
        refreshFromStorage()
    }

    private fun setupDrawer() {
        binding.drawerToggle.setOnClickListener {
            if (binding.drawerLayout.isDrawerOpen(binding.configDrawer)) {
                binding.drawerLayout.closeDrawer(binding.configDrawer)
            } else {
                binding.drawerLayout.openDrawer(binding.configDrawer)
            }
        }
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerClosed(drawerView: android.view.View) {
                saveConfigFromInputs()
            }
        })
    }

    private fun setupTopics() {
        adapter = TopicAdapter(topics, ::editTopic, ::deleteTopic)
        binding.topicsList.layoutManager = LinearLayoutManager(this)
        binding.topicsList.adapter = adapter
        binding.addTopicButton.setOnClickListener { showTopicDialog(null) }
    }

    private fun setupServerForm() {
        binding.importButton.setOnClickListener {
            importLauncher.launch(arrayOf("application/json"))
        }
        binding.exportButton.setOnClickListener {
            saveConfigFromInputs()
            exportLauncher.launch("mqttnotify-config.json")
        }
        binding.connectButton.setOnClickListener {
            saveConfigFromInputs()
            startMqttService(MqttForegroundService.ACTION_CONNECT)
        }
        binding.disconnectButton.setOnClickListener {
            startMqttService(MqttForegroundService.ACTION_DISCONNECT)
        }
        binding.pickCertButton.setOnClickListener {
            KeyChain.choosePrivateKeyAlias(
                this,
                { alias ->
                    if (alias != null) {
                        binding.clientCertAlias.setText(alias)
                        saveConfigFromInputs()
                    }
                },
                null,
                null,
                null,
                null
            )
        }
        binding.saveButton.setOnClickListener {
            saveConfigFromInputs()
            Toast.makeText(this, R.string.config_saved, Toast.LENGTH_SHORT).show()
        }
        binding.hostInput.addTextChangedListener(simpleWatcher { updateClientIdHint() })
    }

    private fun updateClientIdHint() {
        if (binding.clientIdInput.text.isNullOrBlank()) {
            binding.clientIdInput.hint = "mqttnotify-${System.currentTimeMillis()}"
        }
    }

    private fun refreshFromStorage() {
        val config = storage.load()
        currentConfig = config
        binding.hostInput.setText(config.server.host)
        binding.portInput.setText(config.server.port.toString())
        binding.clientIdInput.setText(config.server.clientId)
        binding.usernameInput.setText(config.server.username)
        binding.passwordInput.setText(config.server.password ?: "")
        binding.clientCertAlias.setText(config.server.clientCertAlias ?: "")
        topics.clear()
        topics.addAll(config.topics)
        adapter.notifyDataSetChanged()
    }

    private fun saveConfigFromInputs() {
        val host = binding.hostInput.text.toString().trim()
        val port = binding.portInput.text.toString().toIntOrNull() ?: 8883
        val clientId = binding.clientIdInput.text.toString().ifBlank { "mqttnotify-${System.currentTimeMillis()}" }
        val username = binding.usernameInput.text.toString()
        val password = binding.passwordInput.text.toString().ifBlank { null }
        val certAlias = binding.clientCertAlias.text.toString().ifBlank { null }
        val server = ServerConfig(host, port, clientId, username, password, certAlias)
        val config = AppConfig(server, topics.toList())
        storage.save(config)
        currentConfig = config
    }

    private fun showTopicDialog(existing: TopicConfig?) {
        val dialogBinding = DialogTopicBinding.inflate(LayoutInflater.from(this))
        val qosAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("0", "1", "2"))
        dialogBinding.qosSpinner.adapter = qosAdapter
        if (existing != null) {
            dialogBinding.topicInput.setText(existing.topic)
            dialogBinding.qosSpinner.setSelection(existing.qos)
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.add_topic else R.string.edit_topic)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val topic = dialogBinding.topicInput.text.toString().trim()
                if (topic.isBlank()) {
                    Toast.makeText(this, R.string.topic_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val qos = dialogBinding.qosSpinner.selectedItem.toString().toInt()
                if (existing == null) {
                    topics.add(TopicConfig(topic, qos))
                } else {
                    val index = topics.indexOf(existing)
                    if (index >= 0) {
                        topics[index] = TopicConfig(topic, qos)
                    }
                }
                adapter.notifyDataSetChanged()
                saveConfigFromInputs()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editTopic(position: Int) {
        val topic = topics.getOrNull(position) ?: return
        showTopicDialog(topic)
    }

    private fun deleteTopic(position: Int) {
        topics.removeAt(position)
        adapter.notifyItemRemoved(position)
        saveConfigFromInputs()
    }

    private fun exportConfig(uri: Uri) {
        val config = currentConfig ?: storage.load()
        val json = storage.exportConfig(config)
        contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(json.toByteArray())
        }
        Toast.makeText(this, R.string.config_exported, Toast.LENGTH_SHORT).show()
    }

    private fun importConfig(uri: Uri) {
        val existingPassword = currentConfig?.server?.password
        val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
        if (raw.isNullOrBlank()) {
            Toast.makeText(this, R.string.config_import_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val config = storage.importConfig(raw, existingPassword)
        storage.save(config)
        refreshFromStorage()
        Toast.makeText(this, R.string.config_imported, Toast.LENGTH_SHORT).show()
    }

    private fun startMqttService(action: String) {
        val intent = Intent(this, MqttForegroundService::class.java).apply { this.action = action }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun simpleWatcher(onChange: () -> Unit): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                onChange()
            }
        }
    }
}
