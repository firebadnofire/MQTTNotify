package org.archuser.mqttnotify.mqtt

import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

class KeystoreTlsProvider(
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
) {
    fun createSocketFactory(clientKeyAlias: String?): SSLSocketFactory {
        val keyManagers = clientKeyAlias?.let { alias ->
            if (!keyStore.containsAlias(alias)) {
                throw IllegalArgumentException("Client key alias '$alias' not found in Android Keystore.")
            }
            buildKeyManagers()
        }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
        val context = SSLContext.getInstance("TLS")
        context.init(keyManagers, trustManagers, SecureRandom())
        return context.socketFactory
    }

    private fun buildKeyManagers(): Array<KeyManager> {
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, null)
        return keyManagerFactory.keyManagers
    }
}
