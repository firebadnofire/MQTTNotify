package org.archuser.mqttnotify.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import org.archuser.mqttnotify.databinding.FragmentHomeBinding
import org.archuser.mqttnotify.mqtt.MqttStatus

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val homeViewModel =
            ViewModelProvider(this).get(HomeViewModel::class.java)

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val textView: TextView = binding.textHome
        homeViewModel.status.observe(viewLifecycleOwner) { status ->
            textView.text = statusText(status)
        }
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun statusText(status: MqttStatus): String = when (status) {
        MqttStatus.Connecting -> getString(org.archuser.mqttnotify.R.string.mqtt_status_connecting)
        MqttStatus.Connected -> getString(org.archuser.mqttnotify.R.string.mqtt_status_connected)
        is MqttStatus.Retrying -> getString(
            org.archuser.mqttnotify.R.string.mqtt_status_retrying,
            status.attempt,
            status.delayMs / 1000
        )
        MqttStatus.Disconnected -> getString(org.archuser.mqttnotify.R.string.mqtt_status_disconnected)
        is MqttStatus.FailedRetrying -> getString(org.archuser.mqttnotify.R.string.mqtt_status_retrying_generic)
    }
}
