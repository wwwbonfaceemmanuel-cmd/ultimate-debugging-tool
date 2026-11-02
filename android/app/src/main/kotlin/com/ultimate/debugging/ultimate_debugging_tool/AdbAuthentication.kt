
package com.ultimate.debugging.ultimate_debugging_tool

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey

/**
 * Handles RSA authentication for the Android Debug Bridge protocol.
 *
 * The private key is generated and retained in Android Keystore.
 * The target Android device must approve the public key.
 */
class AdbAuthentication {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "ultimate_debugging_tool_adb_key"

        private const val RSA_KEY_SIZE = 2048
        private const val RSA_WORD_COUNT = 64
        private const val RSA_MODULUS_SIZE = 256

        private const val KEY_COMMENT = "ultimate-debugging-tool"

        private const val ADB_AUTH_TOKEN = AdbProtocol.AUTH_TOKEN
        private const val ADB_AUTH_SIGNATURE = AdbProtocol.AUTH_SIGNATURE
        private const val ADB_AUTH_PUBLIC_KEY = AdbProtocol.AUTH_PUBLIC_KEY
    }

    private var keyPair: KeyPair? = null

    enum class AuthenticationStage {
        NOT_STARTED,
        SIGNATURE_CREATED,
        PUBLIC_KEY_SENT,
        AUTHORIZED
    }

    @Volatile
    var stage: AuthenticationStage =
        AuthenticationStage.NOT_STARTED
        private set

    /**
     * Loads the existing RSA key or generates a new one.
     */
    @Synchronized
    @Throws(Exception::class)
    fun initialize() {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)

        val existingPrivateKey = keyStore.getKey(
            KEY_ALIAS,
            null
        ) as? java.security.PrivateKey

        val existingPublicKey = keyStore.getCertificate(
            KEY_ALIAS
        )?.publicKey

        if (existingPrivateKey != null && existingPublicKey != null) {
            val publicKey = existingPublicKey as? RSAPublicKey
                ?: throw IllegalStateException(
                    "Stored ADB public key is not RSA."
                )

            require(publicKey.modulus.bitLength() == RSA_KEY_SIZE) {
                "Stored ADB key has an unsupported size."
            }

            keyPair = KeyPair(
                existingPublicKey,
                existingPrivateKey
            )

            return
        }

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            KEYSTORE_PROVIDER
        )

        val specification = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN
        )
            .setKeySize(RSA_KEY_SIZE)
            .setDigests(
                KeyProperties.DIGEST_SHA1,
                KeyProperties.DIGEST_SHA256
            )
            .setSignaturePaddings(
                KeyProperties.SIGNATURE_PADDING_RSA_PKCS1
            )
            .build()

        generator.initialize(specification)

        keyPair = generator.generateKeyPair()
    }

    /**
     * Signs the 20-byte ADB authentication token.
     */
    @Synchronized
    @Throws(Exception::class)
    fun signToken(token: ByteArray): ByteArray {
        require(token.size == 20) {
            "ADB authentication token must contain exactly 20 bytes."
        }

        val pair = requireKeyPair()

        val signer = Signature.getInstance("SHA1withRSA")

        signer.initSign(pair.private)
        signer.update(token)

        val signature = signer.sign()

        require(signature.size == RSA_MODULUS_SIZE) {
            "Unexpected RSA signature length."
        }

        stage = AuthenticationStage.SIGNATURE_CREATED

        return signature
    }

    /**
     * Creates the ADB-compatible RSA public key structure.
     */
    @Synchronized
    @Throws(Exception::class)
    fun createPublicKeyPayload(): ByteArray {
        val publicKey = requireRsaPublicKey()

        val modulus = publicKey.modulus
        val exponent = publicKey.publicExponent

        require(modulus.bitLength() == RSA_KEY_SIZE) {
            "ADB requires a 2048-bit RSA host key."
        }

        require(
            exponent.signum() > 0 &&
            exponent.bitLength() <= 32 &&
            exponent.testBit(0)
        ) {
            "RSA public exponent is invalid for the ADB key format."
        }

        val modulusBytes = toFixedLittleEndian(
            modulus,
            RSA_MODULUS_SIZE
        )

        // ADB requires -N^-1 modulo 2^32.
        val modulusLowWord = BigInteger(
            1,
            modulusBytes.copyOfRange(0, 4).reversedArray()
        )

        val twoTo32 = BigInteger.ONE.shiftLeft(32)

        val n0inv = modulusLowWord
            .modInverse(twoTo32)
            .negate()
            .mod(twoTo32)

        // R^2 modulo N, where R = 2^2048.
        val rr = BigInteger.ONE
            .shiftLeft(RSA_KEY_SIZE * 2)
            .mod(modulus)

        val rrBytes = toFixedLittleEndian(
            rr,
            RSA_MODULUS_SIZE
        )

        val structure = ByteBuffer
            .allocate(
                4 +
                4 +
                RSA_MODULUS_SIZE +
                RSA_MODULUS_SIZE +
                4
            )
            .order(ByteOrder.LITTLE_ENDIAN)

        structure.putInt(RSA_WORD_COUNT)
        structure.putInt(n0inv.toLong().toInt())
        structure.put(modulusBytes)
        structure.put(rrBytes)
        structure.putInt(exponent.toInt())

        val encoded = Base64.encodeToString(
            structure.array(),
            Base64.NO_WRAP
        )

        val payload = "$encoded $KEY_COMMENT\u0000"

        return payload.toByteArray(Charsets.UTF_8)
    }

    /**
     * Creates an ADB AUTH signature packet.
     */
    @Synchronized
    @Throws(Exception::class)
    fun createSignaturePacket(
        token: ByteArray
    ): AdbProtocol.AdbPacket {
        return AdbProtocol.AdbPacket(
            command = AdbProtocol.CMD_AUTH,
            arg0 = ADB_AUTH_SIGNATURE,
            arg1 = 0,
            data = signToken(token)
        )
    }

    /**
     * Creates an ADB AUTH public-key packet.
     *
     * Call markPublicKeySent() only after the packet has
     * successfully been transmitted.
     */
    @Synchronized
    @Throws(Exception::class)
    fun createPublicKeyPacket(): AdbProtocol.AdbPacket {
        return AdbProtocol.AdbPacket(
            command = AdbProtocol.CMD_AUTH,
            arg0 = ADB_AUTH_PUBLIC_KEY,
            arg1 = 0,
            data = createPublicKeyPayload()
        )
    }

    /**
     * Records that the public-key packet was transmitted.
     */
    @Synchronized
    fun markPublicKeySent() {
        stage = AuthenticationStage.PUBLIC_KEY_SENT
    }

    /**
     * Marks authentication complete after receiving a valid CNXN.
     */
    @Synchronized
    fun markAuthorized() {
        stage = AuthenticationStage.AUTHORIZED
    }

    /**
     * Resets the authentication stage for a new handshake.
     */
    @Synchronized
    fun resetStage() {
        stage = AuthenticationStage.NOT_STARTED
    }

    /**
     * Returns the ADB public key as a Base64 payload.
     */
    @Synchronized
    @Throws(Exception::class)
    fun getPublicKeyBase64(): String {
        val publicKey = requireRsaPublicKey()

        return Base64.encodeToString(
            publicKey.encoded,
            Base64.NO_WRAP
        )
    }

    /**
     * Returns a SHA-256 fingerprint of the X.509 public key.
     */
    @Synchronized
    @Throws(Exception::class)
    fun getPublicKeyFingerprint(): String {
        val publicKey = requireRsaPublicKey()

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(publicKey.encoded)

        return digest.joinToString(":") {
            "%02X".format(it.toInt() and 0xFF)
        }
    }

    /**
     * Retrieves the initialized RSA key pair.
     */
    private fun requireKeyPair(): KeyPair {
        return keyPair ?: throw IllegalStateException(
            "ADB authentication has not been initialized."
        )
    }

    /**
     * Retrieves and validates the RSA public key.
     */
    private fun requireRsaPublicKey(): RSAPublicKey {
        return requireKeyPair().public as? RSAPublicKey
            ?: throw IllegalStateException(
                "ADB host key is not an RSA public key."
            )
    }

    /**
     * Converts a positive integer to fixed-size little-endian bytes.
     */
    private fun toFixedLittleEndian(
        value: BigInteger,
        size: Int
    ): ByteArray {
        require(value.signum() >= 0) {
            "Negative RSA integer is not supported."
        }

        val bigEndian = value.toByteArray()

        val unsigned = if (
            bigEndian.size > 1 &&
            bigEndian[0] == 0.toByte()
        ) {
            bigEndian.copyOfRange(1, bigEndian.size)
        } else {
            bigEndian
        }

        require(unsigned.size <= size) {
            "RSA integer exceeds the expected size."
        }

        val result = ByteArray(size)

        for (index in unsigned.indices) {
            result[index] = unsigned[unsigned.size - 1 - index]
        }

        return result
    }
}
