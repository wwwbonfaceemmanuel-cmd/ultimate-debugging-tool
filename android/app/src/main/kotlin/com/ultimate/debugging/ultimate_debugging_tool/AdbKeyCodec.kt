package com.ultimate.debugging.ultimate_debugging_tool

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

/**
 * Pure helpers for the ADB RSA key format and signature layout.
 *
 * No Android classes are used here so the exact byte layouts can be
 * checked by automated tests on a normal JVM.
 */
object AdbKeyCodec {

    const val RSA_KEY_BITS = 2048
    const val RSA_KEY_BYTES = RSA_KEY_BITS / 8
    const val TOKEN_SIZE = 20

    private const val RSA_WORD_COUNT = RSA_KEY_BYTES / 4

    /** ASN.1 DigestInfo prefix for a SHA-1 digest (RFC 8017, section 9.2). */
    private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E,
        0x03, 0x02, 0x1A, 0x05, 0x00, 0x04, 0x14
    )

    /**
     * ADB treats the 20-byte token as an already computed SHA-1 digest.
     * The data to sign is therefore DigestInfo(SHA-1) + token, with
     * PKCS#1 v1.5 padding and no further hashing.
     */
    fun digestInfoForToken(token: ByteArray): ByteArray {
        require(token.size == TOKEN_SIZE) {
            "ADB authentication token must contain exactly 20 bytes."
        }

        return SHA1_DIGEST_INFO_PREFIX + token
    }

    /** Full PKCS#1 v1.5 signature block: 00 01 FF..FF 00 DigestInfo. */
    fun pkcs1SignatureBlock(digestInfo: ByteArray): ByteArray {
        require(digestInfo.size <= RSA_KEY_BYTES - 11) {
            "Data is too long for the RSA key."
        }

        val block = ByteArray(RSA_KEY_BYTES)
        block[0] = 0x00
        block[1] = 0x01

        val paddingEnd = RSA_KEY_BYTES - digestInfo.size - 1

        for (index in 2 until paddingEnd) {
            block[index] = 0xFF.toByte()
        }

        block[paddingEnd] = 0x00

        System.arraycopy(
            digestInfo,
            0,
            block,
            paddingEnd + 1,
            digestInfo.size
        )

        return block
    }

    /**
     * Checks a signature the same way the phone does: apply the public
     * key and compare with the expected PKCS#1 block.
     */
    fun verifyTokenSignature(
        publicKey: RSAPublicKey,
        token: ByteArray,
        signature: ByteArray
    ): Boolean {
        if (signature.size != RSA_KEY_BYTES) return false

        val recovered = BigInteger(1, signature)
            .modPow(publicKey.publicExponent, publicKey.modulus)

        val expected = BigInteger(
            1,
            pkcs1SignatureBlock(digestInfoForToken(token))
        )

        return recovered == expected
    }

    /**
     * Binary public key structure used by ADB (524 bytes):
     * word count, -1/n mod 2^32, modulus, R^2 mod n, exponent.
     * All numbers are little-endian.
     */
    fun encodePublicKeyBlob(publicKey: RSAPublicKey): ByteArray {
        val modulus = publicKey.modulus
        val exponent = publicKey.publicExponent

        require(modulus.bitLength() == RSA_KEY_BITS) {
            "ADB requires a 2048-bit RSA host key."
        }

        require(
            exponent.signum() > 0 &&
                exponent.bitLength() <= 32 &&
                exponent.testBit(0)
        ) {
            "RSA public exponent is invalid for the ADB key format."
        }

        val twoTo32 = BigInteger.ONE.shiftLeft(32)

        val n0inv = modulus
            .mod(twoTo32)
            .modInverse(twoTo32)
            .negate()
            .mod(twoTo32)

        val rr = BigInteger.ONE
            .shiftLeft(RSA_KEY_BITS * 2)
            .mod(modulus)

        val structure = ByteBuffer
            .allocate(4 + 4 + RSA_KEY_BYTES + RSA_KEY_BYTES + 4)
            .order(ByteOrder.LITTLE_ENDIAN)

        structure.putInt(RSA_WORD_COUNT)
        structure.putInt(n0inv.toLong().toInt())
        structure.put(toFixedLittleEndian(modulus, RSA_KEY_BYTES))
        structure.put(toFixedLittleEndian(rr, RSA_KEY_BYTES))
        structure.putInt(exponent.toLong().toInt())

        return structure.array()
    }

    /** Payload of the AUTH public-key packet: base64 blob, comment, NUL. */
    fun encodePublicKeyPayload(
        publicKey: RSAPublicKey,
        comment: String
    ): ByteArray {
        val text = base64(encodePublicKeyBlob(publicKey)) +
            " " + comment + "\u0000"

        return text.toByteArray(Charsets.UTF_8)
    }

    /**
     * Fingerprint shown by Android in the "Allow USB debugging?" prompt:
     * MD5 of the binary key structure, as colon-separated hex.
     */
    fun deviceStyleFingerprint(publicKey: RSAPublicKey): String {
        return hexWithColons(
            MessageDigest.getInstance("MD5")
                .digest(encodePublicKeyBlob(publicKey))
        )
    }

    /** SHA-256 of the standard X.509 encoding of the public key. */
    fun sha256Fingerprint(publicKey: RSAPublicKey): String {
        return hexWithColons(
            MessageDigest.getInstance("SHA-256")
                .digest(publicKey.encoded)
        )
    }

    fun hexWithColons(bytes: ByteArray): String {
        return bytes.joinToString(":") {
            "%02X".format(it.toInt() and 0xFF)
        }
    }

    private fun toFixedLittleEndian(
        value: BigInteger,
        size: Int
    ): ByteArray {
        require(value.signum() >= 0) {
            "Negative RSA integer is not supported."
        }

        val bigEndian = value.toByteArray()

        val start = if (
            bigEndian.size > 1 &&
            bigEndian[0] == 0.toByte()
        ) {
            1
        } else {
            0
        }

        val length = bigEndian.size - start

        require(length <= size) {
            "RSA integer exceeds the expected size."
        }

        val result = ByteArray(size)

        for (index in 0 until length) {
            result[index] = bigEndian[bigEndian.size - 1 - index]
        }

        return result
    }

    private const val BASE64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Standard base64 with padding and no line breaks. */
    fun base64(data: ByteArray): String {
        val output = StringBuilder((data.size + 2) / 3 * 4)
        var index = 0

        while (index < data.size) {
            val b0 = data[index].toInt() and 0xFF
            val b1 = if (index + 1 < data.size) {
                data[index + 1].toInt() and 0xFF
            } else {
                0
            }
            val b2 = if (index + 2 < data.size) {
                data[index + 2].toInt() and 0xFF
            } else {
                0
            }

            output.append(BASE64_ALPHABET[b0 shr 2])
            output.append(BASE64_ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
            output.append(
                if (index + 1 < data.size) {
                    BASE64_ALPHABET[((b1 and 0x0F) shl 2) or (b2 shr 6)]
                } else {
                    '='
                }
            )
            output.append(
                if (index + 2 < data.size) {
                    BASE64_ALPHABET[b2 and 0x3F]
                } else {
                    '='
                }
            )

            index += 3
        }

        return output.toString()
    }
}
