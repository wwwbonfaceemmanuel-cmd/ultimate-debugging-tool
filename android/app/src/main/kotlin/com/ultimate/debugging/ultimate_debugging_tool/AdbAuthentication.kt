
package com.ultimate.debugging.ultimate_debugging_tool

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * Handles RSA authentication for the ADB protocol.
 *
 * Responsibilities:
 * - Generate and persist an RSA host key in Android Keystore.
 * - Sign ADB authentication tokens.
 * - Generate an ADB-compatible public key payload.
 *
 * This class does not grant access to a target device.
 * The target device must approve the public key.
 */
class AdbAuthentication {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "ultimate_debugging_tool_adb_key"

        private const val RSA_KEY_SIZE = 2048
        private const val RSA_WORD_COUNT = 64
        private const val RSA_MODULUS_SIZE = 256

        private const val AUTH_SIGNATURE = 2
        private const val AUTH_PUBLIC_KEY = 3

        private const val KEY_COMMENT = "ultimate-debugging-tool"
    }

    private var keyPair: KeyPair? = null

    enum class AuthenticationStage {
        NOT_STARTED,
        SIGNATURE_CREATED,
        PUBLIC_KEY_SENT,
        AUTHORIZED
    }

    var stage: AuthenticationStage =
        AuthenticationStage.NOT_STARTED
        private set

    /**
     * Loads an existing host key or generates a new one.
     *
     * The private key remains inside Android Keystore.
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
     * Signs an ADB authentication token.
     *
     * ADB uses SHA1withRSA for its AUTH_SIGNATURE response.
     */
    @Synchronized
    @Throws(Exception::class)
    fun signToken(token: ByteArray): ByteArray {
        require(token.size == 20) {
            "ADB authentication token must contain 20 bytes."
        }

        val pair = requireKeyPair()

        val signer = Signature.getInstance("SHA1withRSA")

        signer.initSign(pair.private)
        signer.update(token)

        val signature = signer.sign()

        stage = AuthenticationStage.SIGNATURE_CREATED

        return signature
    }

    /**
     * Creates the ADB-compatible RSA public key payload.
     *
     * The payload contains the Android ADB RSA public-key structure,
     * followed by a Base64 encoding and a null-terminated comment.
     */
    @Synchronized
    @Throws(Exception::class)
    fun createPublicKeyPayload(): ByteArray {
        val pair = requireKeyPair()

        val publicKey = pair.public as? RSAPublicKey
            ?: throw IllegalStateException(
                "ADB host key is not an RSA public key."
            )

        val modulus = publicKey.modulus
        val exponent = publicKey.publicExponent

        require(modulus.bitLength() == RSA_KEY_SIZE) {
            "ADB requires a 2048-bit RSA host key."
        }

        val modulusBytes = toFixedLittleEndian(
            modulus,
            RSA_MODULUS_SIZE
        )

        val modulusLowWord = BigInteger(
            1,
            modulusBytes.copyOfRange(0, 4).reversedArray()
        )

        val twoTo32 = BigInteger.ONE.shiftLeft(32)

        val n0inv = twoTo32
            .modInverse(modulusLowWord)
            .negate()
            .mod(twoTo32)

        val rr = BigInteger.ONE
            .shiftLeft(RSA_KEY_SIZE * 2)
            .mod(modulus)

        val rrBytes = toFixedLittleEndian(
            rr,
            RSA_MODULUS_SIZE
        )

        val structure = ByteBuffer
            .allocate(4 + 4 + RSA_MODULUS_SIZE + RSA_MODULUS_SIZE + 4)
            .order(ByteOrder.LITTLE_ENDIAN)

        structure.putInt(RSA_WORD_COUNT)
        structure.putInt(n0inv.toLong().toInt())
        structure.put(modulusBytes)
        structure.put(rrBytes)
        structure.putInt(exponent.toInt())

        val encoded = Base64.getEncoder()
            .encodeToString(structure.array())

        val payload = "$encoded $KEY_COMMENT\u0000"

        stage = AuthenticationStage.PUBLIC_KEY_SENT

        return payload.toByteArray(Charsets.UTF_8)
    }

    /**
     * Builds an ADB AUTH packet containing a signature.
     */
    fun createSignaturePacket(
        token: ByteArray
    ): AdbProtocol.AdbPacket {
        return AdbProtocol.AdbPacket(
            command = AdbProtocol.CMD_AUTH,
            arg0 = AUTH_SIGNATURE,
            arg1 = 0,
            data = signToken(token)
        )
    }

    /**
     * Builds an ADB AUTH packet containing the public key.
     *
     * Send this after the device requests authentication again
     * following the signature response.
     */
    fun createPublicKeyPacket(): AdbProtocol.AdbPacket {
        return AdbProtocol.AdbPacket(
            command = AdbProtocol.CMD_AUTH,
            arg0 = AUTH_PUBLIC_KEY,
            arg1 = 0,
            data = createPublicKeyPayload()
        )
    }

    /**
     * Marks authentication as completed after the connection
     * manager receives a valid CNXN response.
     */
    @Synchronized
    fun markAuthorized() {
        stage = AuthenticationStage.AUTHORIZED
    }

    /**
     * Returns the current public key in readable Base64 form.
     *
     * This is not the private key.
     */
    @Synchronized
    @Throws(Exception::class)
    fun getPublicKeyFingerprint(): String {
        val pair = requireKeyPair()

        return Base64.getEncoder().encodeToString(
            pair.public.encoded
        )
    }

    /**
     * Ensures the key pair has been initialized.
     */
    private fun requireKeyPair(): KeyPair {
        return keyPair ?: throw IllegalStateException(
            "ADB authentication has not been initialized."
        )
    }

    /**
     * Converts a positive integer into a fixed-size
     * little-endian byte array.
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
            bigEndian.size > 1 && bigEndian[0] == 0.toByte()
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
