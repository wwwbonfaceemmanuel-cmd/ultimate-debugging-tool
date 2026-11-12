package com.ultimate.debugging.ultimate_debugging_tool

import java.math.BigInteger
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

/**
 * The RSA identity this workstation presents to target phones.
 *
 * Implementations decide where the private key lives (Android Keystore
 * or app-private storage). See AdbKeyStore.kt.
 */
interface AdbKey {

    val publicKey: RSAPublicKey

    /** Human-readable description of where the private key is stored. */
    val storage: String

    /**
     * Produces a PKCS#1 v1.5 RSA signature over [digestInfo] exactly as
     * given. The data must NOT be hashed again.
     */
    @Throws(Exception::class)
    fun signDigestInfo(digestInfo: ByteArray): ByteArray
}

/**
 * RSA key held in memory. Used when the Android Keystore cannot produce
 * ADB-compatible signatures, and by automated tests.
 */
class SoftwareAdbKey(
    override val publicKey: RSAPublicKey,
    private val privateKey: RSAPrivateKey,
    override val storage: String = "App-private storage"
) : AdbKey {

    override fun signDigestInfo(digestInfo: ByteArray): ByteArray {
        val block = BigInteger(
            1,
            AdbKeyCodec.pkcs1SignatureBlock(digestInfo)
        )

        val signature = block
            .modPow(privateKey.privateExponent, privateKey.modulus)
            .toByteArray()

        // Normalise to exactly 256 bytes, big-endian.
        val result = ByteArray(AdbKeyCodec.RSA_KEY_BYTES)

        val start = if (signature.size > result.size) {
            signature.size - result.size
        } else {
            0
        }

        val length = signature.size - start

        System.arraycopy(
            signature,
            start,
            result,
            result.size - length,
            length
        )

        return result
    }
}

/**
 * Handles RSA authentication for the Android Debug Bridge protocol.
 *
 * The target phone first receives a signature. If it does not know this
 * key yet, it receives the public key and asks its user to approve it.
 * When the user ticks "Always allow", later connections are accepted
 * from the signature alone, with no prompt.
 */
class AdbAuthentication(
    private val keyProvider: () -> AdbKey
) {

    companion object {
        const val KEY_COMMENT = "ultimate-debugging-tool"
    }

    enum class AuthenticationStage {
        NOT_STARTED,
        SIGNATURE_CREATED,
        PUBLIC_KEY_SENT,
        AUTHORIZED
    }

    @Volatile
    var stage: AuthenticationStage = AuthenticationStage.NOT_STARTED
        private set

    @Volatile
    private var key: AdbKey? = null

    /** Loads the RSA key, creating it on first use. May be slow. */
    @Synchronized
    @Throws(Exception::class)
    fun initialize() {
        if (key == null) {
            key = keyProvider()
        }
    }

    /** Signs the 20-byte ADB authentication token. */
    @Synchronized
    @Throws(Exception::class)
    fun signToken(token: ByteArray): ByteArray {
        val activeKey = requireKey()

        val signature = activeKey.signDigestInfo(
            AdbKeyCodec.digestInfoForToken(token)
        )

        require(signature.size == AdbKeyCodec.RSA_KEY_BYTES) {
            "Unexpected RSA signature length."
        }

        stage = AuthenticationStage.SIGNATURE_CREATED
        return signature
    }

    @Synchronized
    @Throws(Exception::class)
    fun createSignaturePacket(
        token: ByteArray
    ): AdbProtocol.AdbPacket {
        return AdbProtocol.AdbPacket(
            command = AdbProtocol.CMD_AUTH,
            arg0 = AdbProtocol.AUTH_SIGNATURE,
            arg1 = 0,
            data = signToken(token)
        )
    }

    /**
     * Creates the AUTH public-key packet.
     * Call markPublicKeySent() after it has been transmitted.
     */
    @Synchronized
    @Throws(Exception::class)
    fun createPublicKeyPacket(): AdbProtocol.AdbPacket {
        return AdbProtocol.AdbPacket(
            command = AdbProtocol.CMD_AUTH,
            arg0 = AdbProtocol.AUTH_PUBLIC_KEY,
            arg1 = 0,
            data = AdbKeyCodec.encodePublicKeyPayload(
                requireKey().publicKey,
                KEY_COMMENT
            )
        )
    }

    @Synchronized
    fun markPublicKeySent() {
        stage = AuthenticationStage.PUBLIC_KEY_SENT
    }

    @Synchronized
    fun markAuthorized() {
        stage = AuthenticationStage.AUTHORIZED
    }

    @Synchronized
    fun resetStage() {
        stage = AuthenticationStage.NOT_STARTED
    }

    /**
     * Fingerprint in the same form the target phone shows in its
     * "Allow USB debugging?" prompt, so the two can be compared.
     */
    @Synchronized
    @Throws(Exception::class)
    fun getDeviceStyleFingerprint(): String {
        return AdbKeyCodec.deviceStyleFingerprint(requireKey().publicKey)
    }

    /** SHA-256 fingerprint of the X.509 public key. */
    @Synchronized
    @Throws(Exception::class)
    fun getPublicKeyFingerprint(): String {
        return AdbKeyCodec.sha256Fingerprint(requireKey().publicKey)
    }

    @Synchronized
    fun getKeyStorage(): String {
        return requireKey().storage
    }

    private fun requireKey(): AdbKey {
        return key ?: throw IllegalStateException(
            "ADB authentication has not been initialized."
        )
    }
}
