package org.archuser.mqttnotify

import android.app.Activity
import android.security.KeyChain

object ClientCertificateHelper {

    fun chooseClientCertificate(activity: Activity, onChosen: (String?) -> Unit) {
        KeyChain.choosePrivateKeyAlias(
            activity,
            { alias -> onChosen(alias) },
            null,
            null,
            null,
            -1,
            null
        )
    }
}
