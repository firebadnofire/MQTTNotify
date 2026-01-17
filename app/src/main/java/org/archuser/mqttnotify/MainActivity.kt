package org.archuser.mqttnotify

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.archuser.mqttnotify.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesStore

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

        bindValues()
        setupActions()
        requestNotificationPermissionIfNeeded()
    }

    private fun bindValues() {
        val config = prefs.loadConfig()
        binding.brokerUriInput.setText(config.brokerUri)
        binding.clientIdInput.setText(config.clientId)
        binding.usernameInput.setText(config.username)
        binding.passwordInput.setText(config.password)
        binding.topicsInput.setText(config.topics.joinToString("\n"))
        binding.clientCertAliasValue.text = config.clientCertAlias ?: getString(R.string.no_cert_selected)
    }

    private fun setupActions() {
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

        binding.startButton.setOnClickListener {
            val updated = prefs.updateConfig { current ->
                current.copy(
                    brokerUri = binding.brokerUriInput.text?.toString()?.trim().orEmpty(),
                    clientId = binding.clientIdInput.text?.toString()?.trim().orEmpty(),
                    username = binding.usernameInput.text?.toString()?.trim().orEmpty(),
                    password = binding.passwordInput.text?.toString()?.trim().orEmpty(),
                    topics = binding.topicsInput.text?.toString()
                        ?.lines()
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty()
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
