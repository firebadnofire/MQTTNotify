package org.archuser.mqttnotify

import android.content.Context
import android.security.KeyChain
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

object TlsSocketFactory {
    fun create(context: Context, alias: String?): SSLSocketFactory {
        val keyManagers = if (!alias.isNullOrBlank()) {
            val privateKey = KeyChain.getPrivateKey(context, alias)
            val chain = KeyChain.getCertificateChain(context, alias)
            if (privateKey != null && chain != null) {
                val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
                keyStore.load(null)
                keyStore.setKeyEntry("client", privateKey, null, chain)
                val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                keyManagerFactory.init(keyStore, null)
                keyManagerFactory.keyManagers
            } else {
                null
            }
        } else {
            null
        }
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(null as KeyStore?)
        val contextTls = SSLContext.getInstance("TLS")
        contextTls.init(keyManagers, trustManagerFactory.trustManagers, null)
        return contextTls.socketFactory
    }
}
