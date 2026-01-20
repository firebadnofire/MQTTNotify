package org.archuser.mqttnotify

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.archuser.mqttnotify.databinding.ActivityMainBinding
import org.archuser.mqttnotify.databinding.DialogAddTopicBinding
import org.archuser.mqttnotify.databinding.DialogImportConfigBinding
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesStore
    private val topics = mutableListOf<String>()

    private val notificationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    R.string.notifications_permission_denied,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = PreferencesStore(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        bindValues()
        setupActions()
        requestNotificationPermissionIfNeeded()
        applyToolbarInsets()
        promptBatteryOptimizationsIfNeeded()
    }

    private fun bindValues() {
        val config = prefs.loadConfig()
        binding.brokerUriInput.setText(config.brokerUri)
        binding.clientIdInput.setText(config.clientId)
        binding.usernameInput.setText(config.username)
        binding.passwordInput.setText(config.password)
        binding.clientCertAliasValue.text = config.clientCertAlias ?: getString(R.string.no_cert_selected)
        topics.clear()
        topics.addAll(config.topics)
        renderTopics()
    }

    private fun setupActions() {
        binding.toolbar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(Gravity.START)
        }

        binding.selectCertButton.setOnClickListener {
            ClientCertificateHelper.chooseClientCertificate(this) { alias ->
                if (alias != null) {
                    prefs.updateConfig { current ->
                        current.copy(clientCertAlias = alias)
                    }
                    binding.clientCertAliasValue.text = alias
                }
            }
        }

        binding.addTopicButton.setOnClickListener {
            showAddTopicDialog()
        }

        binding.exportConfigButton.setOnClickListener {
            exportConfigToClipboard()
        }

        binding.importConfigButton.setOnClickListener {
            showImportConfigDialog()
        }

        binding.startButton.setOnClickListener {
            val updated = prefs.updateConfig { current ->
                current.copy(
                    brokerUri = binding.brokerUriInput.text?.toString()?.trim().orEmpty(),
                    clientId = binding.clientIdInput.text?.toString()?.trim().orEmpty(),
                    username = binding.usernameInput.text?.toString()?.trim().orEmpty(),
                    password = binding.passwordInput.text?.toString()?.trim().orEmpty(),
                    topics = topics.toList()
                )
            }

            if (!updated.brokerUri.startsWith("ssl://")) {
                Toast.makeText(this, R.string.tls_required, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (updated.topics.isEmpty()) {
                Toast.makeText(this, R.string.topics_required, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            startForegroundService(
                Intent(this, MqttForegroundService::class.java).setAction(
                    MqttForegroundService.ACTION_START
                )
            )
        }

        binding.stopButton.setOnClickListener {
            startService(
                Intent(this, MqttForegroundService::class.java).setAction(
                    MqttForegroundService.ACTION_STOP
                )
            )
        }
    }

    private fun applyToolbarInsets() {
        val originalPaddingStart = binding.toolbar.paddingStart
        val originalPaddingEnd = binding.toolbar.paddingEnd
        val originalPaddingTop = binding.toolbar.paddingTop
        val originalPaddingBottom = binding.toolbar.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = originalPaddingStart + systemBars.left,
                top = originalPaddingTop + systemBars.top,
                right = originalPaddingEnd + systemBars.right,
                bottom = originalPaddingBottom
            )
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(binding.toolbar)
    }

    private fun promptBatteryOptimizationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        val powerManager = getSystemService(PowerManager::class.java)
        val packageName = packageName
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_optimization_title)
            .setMessage(R.string.battery_optimization_message)
            .setPositiveButton(R.string.allow_background) { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(
                        Uri.parse("package:$packageName")
                    )
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddTopicDialog() {
        val dialogBinding = DialogAddTopicBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_topic)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.add) { _, _ ->
                val topic = dialogBinding.topicInput.text?.toString()?.trim().orEmpty()
                if (topic.isBlank()) {
                    Toast.makeText(this, R.string.topic_empty, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                topics.add(topic)
                saveTopics()
                renderTopics()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showImportConfigDialog() {
        val dialogBinding = DialogImportConfigBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_config_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.import_config) { _, _ ->
                val payload = dialogBinding.importConfigInput.text?.toString()?.trim().orEmpty()
                if (payload.isBlank()) {
                    Toast.makeText(this, R.string.import_config_error, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (importConfigFromJson(payload)) {
                    Toast.makeText(this, R.string.import_config_success, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, R.string.import_config_error, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderTopics() {
        binding.topicChipGroup.removeAllViews()
        topics.forEach { topic ->
            val chip = Chip(this).apply {
                text = topic
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    topics.remove(topic)
                    saveTopics()
                    renderTopics()
                }
            }
            binding.topicChipGroup.addView(chip)
        }
    }

    private fun saveTopics() {
        prefs.updateConfig { current ->
            current.copy(topics = topics.toList())
        }
    }

    private fun exportConfigToClipboard() {
        val config = prefs.loadConfig()
        val json = JSONObject().apply {
            put("brokerUri", config.brokerUri)
            put("clientId", config.clientId)
            put("username", config.username)
            put("password", config.password)
            put("clientCertAlias", config.clientCertAlias)
            put("topics", JSONArray(config.topics))
        }.toString(2)

        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("MQTTNotify config", json))
        Toast.makeText(this, R.string.export_config_copied, Toast.LENGTH_LONG).show()

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_config_share_title))
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.export_config_share_title)))
    }

    private fun importConfigFromJson(payload: String): Boolean {
        return try {
            val json = JSONObject(payload)
            val imported = MqttConfig(
                brokerUri = json.optString("brokerUri", ""),
                clientId = json.optString("clientId", ""),
                username = json.optString("username", ""),
                password = json.optString("password", ""),
                topics = json.optJSONArray("topics")?.let { array ->
                    (0 until array.length())
                        .mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
                } ?: emptyList(),
                clientCertAlias = json.optString("clientCertAlias").takeIf { it.isNotBlank() }
            )
            prefs.updateConfig { imported }
            topics.clear()
            topics.addAll(imported.topics)
            binding.brokerUriInput.setText(imported.brokerUri)
            binding.clientIdInput.setText(imported.clientId)
            binding.usernameInput.setText(imported.username)
            binding.passwordInput.setText(imported.password)
            binding.clientCertAliasValue.text = imported.clientCertAlias ?: getString(R.string.no_cert_selected)
            renderTopics()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionRequest.launch(permission)
        }
    }
}
