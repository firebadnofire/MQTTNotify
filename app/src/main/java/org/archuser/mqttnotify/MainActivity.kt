package org.archuser.mqttnotify

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import org.archuser.mqttnotify.databinding.ActivityMainBinding
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: TopicAdapter
    private lateinit var configStorage: ConfigStorage
    private var currentConfig = AppConfig(ServerConfig("", 8883, "", null), emptyList())

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importConfig(it) }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { exportConfig(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configStorage = ConfigStorage(this)
        currentConfig = configStorage.loadConfig()

        setupInsets()
        setupToolbar()
        setupList()
        setupActions()
        populateFields()
        observeStatus()
        requestNotificationPermission()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.contentRoot) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerContent) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        val toggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.nav_open,
            R.string.nav_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
    }

    private fun setupList() {
        adapter = TopicAdapter { topic ->
            val updated = currentConfig.topics.filterNot { it.topic == topic.topic }
            currentConfig = currentConfig.copy(topics = updated)
            configStorage.saveConfig(currentConfig)
            adapter.submitList(updated)
            restartServiceIfConnected()
        }
        binding.topicList.layoutManager = LinearLayoutManager(this)
        binding.topicList.adapter = adapter
        adapter.submitList(currentConfig.topics)
    }

    private fun setupActions() {
        binding.addTopic.setOnClickListener { promptAddTopic() }
        binding.connect.setOnClickListener { applyConfigAndConnect() }
        binding.disconnect.setOnClickListener { disconnectService() }
        binding.importConfig.setOnClickListener { importLauncher.launch(arrayOf("application/json", "text/*")) }
        binding.exportConfig.setOnClickListener { exportLauncher.launch("mqttnotify-config.json") }
        binding.selectCert.setOnClickListener { chooseClientCert() }
    }

    private fun populateFields() {
        val (username, password) = configStorage.loadSecrets()
        binding.serverHost.setText(currentConfig.server.host)
        binding.serverPort.setText(currentConfig.server.port.toString())
        binding.clientId.setText(currentConfig.server.clientId)
        binding.username.setText(username ?: "")
        binding.password.setText(password ?: "")
        binding.clientCertAlias.setText(currentConfig.server.clientCertAlias ?: "")
    }

    private fun observeStatus() {
        ConnectionStatusRepository.observe().observe(this, Observer { status ->
            val text = when (status.state) {
                ConnectionState.CONNECTED -> getString(R.string.status_connected)
                ConnectionState.CONNECTING -> getString(R.string.status_connecting)
                ConnectionState.DISCONNECTED -> getString(R.string.status_disconnected)
                ConnectionState.RETRYING -> getString(R.string.status_retrying, status.retryInSeconds ?: 3)
            }
            binding.statusText.text = text
        })
    }

    private fun promptAddTopic() {
        val input = EditText(this)
        input.hint = getString(R.string.topic_hint)
        AlertDialog.Builder(this)
            .setTitle(R.string.add_topic)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val topic = input.text.toString().trim()
                if (topic.isNotBlank()) {
                    val updated = currentConfig.topics + TopicConfig(topic)
                    currentConfig = currentConfig.copy(topics = updated)
                    configStorage.saveConfig(currentConfig)
                    adapter.submitList(updated)
                    restartServiceIfConnected()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyConfigAndConnect() {
        val host = binding.serverHost.text?.toString()?.trim().orEmpty()
        val port = binding.serverPort.text?.toString()?.toIntOrNull() ?: 8883
        val clientId = binding.clientId.text?.toString()?.trim().orEmpty()
        val clientCertAlias = binding.clientCertAlias.text?.toString()?.trim().takeIf { !it.isNullOrBlank() }
        val username = binding.username.text?.toString()?.trim()
        val password = binding.password.text?.toString()
        currentConfig = currentConfig.copy(
            server = ServerConfig(
                host = host,
                port = port,
                clientId = if (clientId.isBlank()) currentConfig.server.clientId else clientId,
                clientCertAlias = clientCertAlias
            )
        )
        configStorage.saveConfig(currentConfig)
        configStorage.saveSecrets(username, password)
        val intent = Intent(this, MqttForegroundService::class.java).apply {
            action = MqttForegroundService.ACTION_CONNECT
        }
        ContextCompat.startForegroundService(this, intent)
        binding.drawerLayout.closeDrawer(Gravity.START)
    }

    private fun disconnectService() {
        val intent = Intent(this, MqttForegroundService::class.java).apply {
            action = MqttForegroundService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    private fun restartServiceIfConnected() {
        if (ConnectionStatusRepository.observe().value?.state == ConnectionState.CONNECTED) {
            applyConfigAndConnect()
        }
    }

    private fun importConfig(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                currentConfig = configStorage.importConfig(stream)
                adapter.submitList(currentConfig.topics)
                populateFields()
            }
        } catch (_: IOException) {
            // Ignore import errors.
        }
    }

    private fun exportConfig(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { stream ->
                configStorage.exportConfig(stream, currentConfig)
            }
        } catch (_: IOException) {
            // Ignore export errors.
        }
    }

    private fun chooseClientCert() {
        android.security.KeyChain.choosePrivateKeyAlias(
            this,
            { alias ->
                runOnUiThread {
                    if (!alias.isNullOrBlank()) {
                        binding.clientCertAlias.setText(alias)
                    }
                }
            },
            arrayOf("RSA", "EC"),
            null,
            null,
            -1,
            null
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(permission), 100)
            }
        }
    }
}
