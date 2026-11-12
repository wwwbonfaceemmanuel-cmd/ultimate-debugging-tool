package com.ultimate.debugging.ultimate_debugging_tool

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec

/**
 * Loads or creates the RSA key this app uses to identify itself to
 * target phones.
 *
 * Preferred storage is the Android Keystore, where the private key can
 * never leave the device. Every key is self-tested: if the Keystore of
 * this particular phone cannot produce the exact signature ADB expects,
 * the app falls back to a key kept in its private storage.
 */
object AdbKeyStore {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /** Version 2: allows signing without re-hashing, as ADB requires. */
    private const val KEY_ALIAS = "ultimate_debugging_tool_adb_key_v2"

    /** Version 1 produced signatures that phones never accepted. */
    private const val LEGACY_KEY_ALIAS = "ultimate_debugging_tool_adb_key"

    private const val SOFTWARE_KEY_FILE = "adb_host_key_v2.pk8"

    private val lock = Any()

    @Volatile
    private var cached: AdbKey? = null

    @Throws(Exception::class)
    fun loadOrCreate(context: Context): AdbKey {
        cached?.let { return it }

        synchronized(lock) {
            cached?.let { return it }

            val appContext = context.applicationContext
            val softwareFile = File(
                appContext.noBackupFilesDir,
                SOFTWARE_KEY_FILE
            )

            removeLegacyKey()

            val key = loadSoftwareKey(softwareFile)
                ?: loadOrCreateKeystoreKey()
                ?: createSoftwareKey(softwareFile)

            cached = key
            return key
        }
    }

    private fun removeLegacyKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            if (keyStore.containsAlias(LEGACY_KEY_ALIAS)) {
                keyStore.deleteEntry(LEGACY_KEY_ALIAS)
            }
        } catch (_: Exception) {
            // The legacy key is unused; failing to remove it is harmless.
        }
    }

    /** Returns a working Keystore key, or null if this phone cannot. */
    private fun loadOrCreateKeystoreKey(): AdbKey? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null

        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA,
                    ANDROID_KEYSTORE
                )

                generator.initialize(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN
                    )
                        .setAlgorithmParameterSpec(
                            RSAKeyGenParameterSpec(
                                AdbKeyCodec.RSA_KEY_BITS,
                                RSAKeyGenParameterSpec.F4
                            )
                        )
                        .setDigests(KeyProperties.DIGEST_NONE)
                        .setSignaturePaddings(
                            KeyProperties.SIGNATURE_PADDING_RSA_PKCS1
                        )
                        .build()
                )

                generator.generateKeyPair()
            }

            val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
            val publicKey = keyStore.getCertificate(KEY_ALIAS)
                ?.publicKey as? RSAPublicKey

            if (privateKey == null || publicKey == null) {
                keyStore.deleteEntry(KEY_ALIAS)
                return null
            }

            val key = KeystoreAdbKey(publicKey, privateKey)

            if (selfTest(key)) {
                key
            } else {
                keyStore.deleteEntry(KEY_ALIAS)
                null
            }
        } catch (_: Exception) {
            try {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
                keyStore.load(null)
                keyStore.deleteEntry(KEY_ALIAS)
            } catch (_: Exception) {
                // Nothing further can be done.
            }

            null
        }
    }

    private fun loadSoftwareKey(file: File): AdbKey? {
        if (!file.isFile) return null

        return try {
            val factory = KeyFactory.getInstance("RSA")

            val privateKey = factory.generatePrivate(
                PKCS8EncodedKeySpec(file.readBytes())
            ) as RSAPrivateKey

            val publicKey = factory.generatePublic(
                RSAPublicKeySpec(
                    privateKey.modulus,
                    BigInteger.valueOf(65537L)
                )
            ) as RSAPublicKey

            val key = SoftwareAdbKey(publicKey, privateKey)

            if (selfTest(key)) key else null
        } catch (_: Exception) {
            null
        }
    }

    private fun createSoftwareKey(file: File): AdbKey {
        val generator = KeyPairGenerator.getInstance("RSA")

        generator.initialize(
            RSAKeyGenParameterSpec(
                AdbKeyCodec.RSA_KEY_BITS,
                RSAKeyGenParameterSpec.F4
            ),
            SecureRandom()
        )

        val pair = generator.generateKeyPair()

        val key = SoftwareAdbKey(
            pair.public as RSAPublicKey,
            pair.private as RSAPrivateKey
        )

        check(selfTest(key)) {
            "This phone could not create a working ADB key."
        }

        // Write to a temporary file first so a crash cannot leave a
        // half-written key behind.
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeBytes(pair.private.encoded)

        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IllegalStateException("Unable to save the ADB key.")
        }

        return key
    }

    /** Signs a sample token and checks it exactly as a phone would. */
    private fun selfTest(key: AdbKey): Boolean {
        return try {
            val token = ByteArray(AdbKeyCodec.TOKEN_SIZE) {
                (it * 7 + 3).toByte()
            }

            val signature = key.signDigestInfo(
                AdbKeyCodec.digestInfoForToken(token)
            )

            AdbKeyCodec.verifyTokenSignature(
                key.publicKey,
                token,
                signature
            )
        } catch (_: Exception) {
            false
        }
    }

    private class KeystoreAdbKey(
        override val publicKey: RSAPublicKey,
        private val privateKey: PrivateKey
    ) : AdbKey {

        override val storage = "Android Keystore"

        override fun signDigestInfo(digestInfo: ByteArray): ByteArray {
            // "NONE" means the data is padded and signed as it is.
            val signer = Signature.getInstance("NONEwithRSA")
            signer.initSign(privateKey)
            signer.update(digestInfo)
            return signer.sign()
        }
    }
}
