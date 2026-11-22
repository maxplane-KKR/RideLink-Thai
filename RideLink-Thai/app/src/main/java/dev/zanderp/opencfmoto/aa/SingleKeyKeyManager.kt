// Ported from headunit-revived (AGPLv3): ssl/SingleKeyKeyManager.kt
// Presents the bundled reference identity to the AAP TLS handshake.
package dev.zanderp.opencfmoto.aa

import android.content.Context
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.TftDiagnostics
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager

internal class SingleKeyKeyManager private constructor(certificate: X509Certificate, privateKey: PrivateKey) : X509ExtendedKeyManager() {

    private val delegate: X509KeyManager

    init {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null)
        ks.setCertificateEntry(DEFAULT_ALIAS, certificate)
        ks.setKeyEntry(DEFAULT_ALIAS, privateKey, charArrayOf(), arrayOf(certificate))

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, charArrayOf())
        delegate = kmf.keyManagers[0] as X509KeyManager
    }

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
        delegate.getClientAliases(keyType, issuers)

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
        delegate.getServerAliases(keyType, issuers)

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String =
        delegate.chooseServerAlias(keyType, issuers, socket)

    override fun getCertificateChain(alias: String?): Array<X509Certificate> =
        delegate.getCertificateChain(alias)

    override fun getPrivateKey(alias: String?): PrivateKey = delegate.getPrivateKey(alias)

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String =
        DEFAULT_ALIAS

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String =
        DEFAULT_ALIAS

    companion object {
        private const val DEFAULT_ALIAS = "defaultSingleKeyAlias"

        private const val ENVELOPE_LABEL = "RideLink TH AAP key envelope v1"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private var cached: SingleKeyKeyManager? = null

        @Synchronized
        fun fromResources(context: Context): SingleKeyKeyManager {
            cached?.let { return it }
            val certificate = context.resources.openRawResource(R.raw.aa_cert).use { input ->
                CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
            }
            val wrapped = context.resources.openRawResource(R.raw.aa_identity_v1).use { it.readBytes() }
            require(wrapped.size > NONCE_BYTES + TAG_BITS / 8)
            // The wrapping input is public. This conceals the PKCS#8 bytes from casual inspection.
            val digest = MessageDigest.getInstance("SHA-256").apply { update(certificate.encoded) }
            val wrappingKey = digest.digest(ENVELOPE_LABEL.toByteArray(Charsets.UTF_8))
            val plain = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val parameters = GCMParameterSpec(TAG_BITS, wrapped, 0, NONCE_BYTES)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(wrappingKey, "AES"), parameters)
                cipher.doFinal(wrapped, NONCE_BYTES, wrapped.size - NONCE_BYTES)
            } finally {
                wrappingKey.fill(0)
            }
            val privateKey = try {
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(plain))
            } finally {
                plain.fill(0)
            }
            return SingleKeyKeyManager(certificate, privateKey).also {
                cached = it
                TftDiagnostics.recordWrappedHeadunitIdentity()
            }
        }
    }
}
