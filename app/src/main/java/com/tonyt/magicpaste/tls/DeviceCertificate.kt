package com.tonyt.magicpaste.tls

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.saveToFile
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.network.tls.extensions.SignatureAlgorithm
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * The self-signed certificate this device serves HTTPS with, and the fingerprint
 * that lets a visitor confirm they are talking to it.
 *
 * Self-signed means browsers will warn once per device. That warning is about
 * *identity*, not secrecy — the connection is fully encrypted either way, which
 * is what takes passive sniffing off the table. Closing the identity gap is what
 * [fingerprint] is for: compare it against what the browser reports and a relay
 * in the middle becomes visible.
 */
class DeviceCertificate private constructor(
    private val keyStore: KeyStore,
    /** The addresses baked into the certificate, so we can tell when it is stale. */
    val addresses: List<String>,
    /** The DNS names baked in — the mDNS hostname, when one is advertised. */
    val hosts: List<String>,
    val fingerprint: Fingerprint,
) {

    /** Configured for the TLS server socket. */
    fun sslContext(): SSLContext {
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).run {
            init(keyStore, PASSWORD.toCharArray())
            keyManagers
        }
        return SSLContext.getInstance("TLS").apply { init(managers, null, null) }
    }

    companion object {

        private const val ALIAS = "magicpaste"
        private const val PASSWORD = "magicpaste"
        private const val FILE_NAME = "magicpaste-tls.jks"
        private const val VALID_DAYS = 3650L

        /**
         * Loads the stored certificate, regenerating it when it does not exist or
         * no longer covers [addresses].
         *
         * The addresses end up in the certificate's SAN list, and DHCP hands out
         * a different one sooner or later. A mismatch is invisible while you are
         * clicking through warnings anyway, but it matters to anyone who took the
         * trouble to install the certificate — so the certificate follows the
         * addresses rather than the other way round.
         */
        fun loadOrCreate(
            directory: File,
            addresses: List<String>,
            hosts: List<String> = emptyList(),
        ): DeviceCertificate {
            val file = File(directory, FILE_NAME)
            val wantedAddresses = addresses.sorted()
            val wantedHosts = hosts.sorted()

            if (file.exists()) {
                val existing = runCatching { read(file) }.getOrNull()
                if (existing != null &&
                    existing.addresses == wantedAddresses &&
                    existing.hosts == wantedHosts
                ) {
                    return existing
                }
            }
            return create(file, wantedAddresses, wantedHosts)
        }

        private fun read(file: File): DeviceCertificate {
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                file.inputStream().use { load(it, PASSWORD.toCharArray()) }
            }
            val certificate = store.getCertificate(ALIAS) as X509Certificate
            return DeviceCertificate(
                keyStore = store,
                addresses = certificate.sanEntries(IP_ADDRESS_SAN_TYPE),
                hosts = certificate.sanEntries(DNS_NAME_SAN_TYPE) - LOCALHOST,
                fingerprint = Fingerprint.of(certificate),
            )
        }

        private fun create(file: File, addresses: List<String>, hosts: List<String>): DeviceCertificate {
            val store = buildKeyStore {
                certificate(ALIAS) {
                    hash = HashAlgorithm.SHA256
                    sign = SignatureAlgorithm.RSA
                    keySizeInBits = 2048
                    password = PASSWORD
                    daysValid = VALID_DAYS
                    subject = X500Principal("CN=MagicPaste, O=MagicPaste")
                    domains = listOf(LOCALHOST) + hosts
                    ipAddresses = addresses.mapNotNull { address ->
                        runCatching { InetAddress.getByName(address) }.getOrNull()
                    }
                }
            }
            file.parentFile?.mkdirs()
            store.saveToFile(file, PASSWORD)
            val certificate = store.getCertificate(ALIAS) as X509Certificate
            return DeviceCertificate(store, addresses, hosts, Fingerprint.of(certificate))
        }

        /** The SAN entries of the given type, sorted for comparison. */
        private fun X509Certificate.sanEntries(type: Int): List<String> =
            runCatching {
                subjectAlternativeNames.orEmpty()
                    .filter { it.size >= 2 && it[0] == type }
                    .mapNotNull { it[1] as? String }
                    .sorted()
            }.getOrDefault(emptyList())

        private const val LOCALHOST = "localhost"
        private const val DNS_NAME_SAN_TYPE = 2
        private const val IP_ADDRESS_SAN_TYPE = 7
    }
}

/**
 * A certificate's SHA-256 digest, in the shapes a person can actually check.
 *
 * Browsers show this as 64 hex characters, so the full form has to be available
 * to compare against — but nobody reads 64 characters. [head] and [tail] are what
 * the UI shows large, since matching the ends is what people really do, and
 * getting those to collide takes work no casual attacker will do.
 */
class Fingerprint(val bytes: ByteArray) {

    /** `A1B2C3D4` — the first four bytes, for comparing at a glance. */
    val head: String = bytes.take(4).toHex()

    /** `E5F60718` — the last four bytes. */
    val tail: String = bytes.takeLast(4).toHex()

    /** `A1:B2:C3:…` in full, matching how browsers present it. */
    val full: String = bytes.joinToString(":") { "%02X".format(it) }

    /** Grouped in fours for the small print: `A1B2 C3D4 E5F6 …`. */
    val grouped: String = bytes.toHex().chunked(4).joinToString(" ")

    private fun List<Byte>.toHex() = joinToString("") { "%02X".format(it) }

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }

    companion object {
        fun of(certificate: X509Certificate): Fingerprint =
            Fingerprint(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))
    }
}
