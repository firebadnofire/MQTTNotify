package org.archuser.mqttnotify.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import org.archuser.mqttnotify.mqtt.MqttStatus
import org.archuser.mqttnotify.mqtt.MqttStatusRepository

class HomeViewModel : ViewModel() {

    val status: LiveData<MqttStatus> = MqttStatusRepository.status
}
