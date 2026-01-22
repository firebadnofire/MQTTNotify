package org.archuser.mqttnotify.mqtt

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object MqttStatusRepository {
    private val mutableStatus = MutableLiveData<MqttStatus>(MqttStatus.Disconnected)
    val status: LiveData<MqttStatus> = mutableStatus

    fun update(status: MqttStatus) {
        mutableStatus.postValue(status)
    }
}
