package org.archuser.mqttnotify

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object ConnectionStatusRepository {
    private val status = MutableLiveData(ConnectionStatus(ConnectionState.DISCONNECTED))

    fun observe(): LiveData<ConnectionStatus> = status

    fun update(newStatus: ConnectionStatus) {
        status.postValue(newStatus)
    }
}
