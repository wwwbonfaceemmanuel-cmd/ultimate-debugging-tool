package com.ultimate.debugging.ultimate_debugging_tool

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class AdbKeyCodecTest {

    companion object {
        private val pair by lazy {
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        }
        private val publicKey get() = pair.public as RSAPublicKey
        private val privateKey get() = pair.private as RSAPrivateKey
    }

    private val token = ByteArray(20) { (it * 11 + 5).toByte() }

    @Test
    fun signatureMatchesJdkPrehashedSignature() {
        // What the phone expects is exactly the JDK's "NONEwithRSA"
        // signature over DigestInfo(SHA-1) + token.
        val digestInfo = AdbKeyCodec.digestInfoForToken(token)

        val expected = Signature.getInstance("NONEwithRSA").run {
            initSign(privateKey)
            update(digestInfo)
            sign()
        }

        val actual = SoftwareAdbKey(publicKey, privateKey).signDigestInfo(digestInfo)

        assertArrayEquals(expected, actual)
        assertTrue(AdbKeyCodec.verifyTokenSignature(publicKey, token, actual))
    }

    @Test
    fun rehashedSignatureIsRejected() {
        // This is what the app used to send: the token hashed once more.
        val wrong = Signature.getInstance("SHA1withRSA").run {
            initSign(privateKey)
            update(token)
            sign()
        }

        assertFalse(AdbKeyCodec.verifyTokenSignature(publicKey, token, wrong))
    }

    @Test
    fun signatureThroughAuthenticationIsValid() {
        val authentication = AdbAuthentication { SoftwareAdbKey(publicKey, privateKey) }
        authentication.initialize()

        val packet = authentication.createSignaturePacket(token)

        assertEquals(AdbProtocol.CMD_AUTH, packet.command)
        assertEquals(AdbProtocol.AUTH_SIGNATURE, packet.arg0)
        assertEquals(256, packet.data.size)
        assertTrue(AdbKeyCodec.verifyTokenSignature(publicKey, token, packet.data))
    }

    @Test
    fun publicKeyBlobHasAdbLayout() {
        val blob = AdbKeyCodec.encodePublicKeyBlob(publicKey)
        val modulus = publicKey.modulus
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(524, blob.size)
        assertEquals(64, buffer.int)

        val n0inv = BigInteger.valueOf(buffer.int.toLong() and 0xFFFFFFFFL)
        val twoTo32 = BigInteger.ONE.shiftLeft(32)
        // n * n0inv must equal -1 modulo 2^32.
        assertEquals(twoTo32.subtract(BigInteger.ONE), modulus.multiply(n0inv).mod(twoTo32))

        assertEquals(modulus, BigInteger(1, blob.copyOfRange(8, 264).reversedArray()))

        val rr = BigInteger(1, blob.copyOfRange(264, 520).reversedArray())
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(modulus), rr)

        assertEquals(65537, ByteBuffer.wrap(blob, 520, 4).order(ByteOrder.LITTLE_ENDIAN).int)
    }

    @Test
    fun publicKeyPayloadIsBase64CommentAndNul() {
        val payload = String(AdbKeyCodec.encodePublicKeyPayload(publicKey, "udt"))

        assertTrue(payload.endsWith(" udt\u0000"))
        assertArrayEquals(
            AdbKeyCodec.encodePublicKeyBlob(publicKey),
            Base64.getDecoder().decode(payload.substringBefore(' '))
        )
    }

    @Test
    fun fingerprintIsMd5OfBlobLikeThePhoneShows() {
        val fingerprint = AdbKeyCodec.deviceStyleFingerprint(publicKey)
        val md5 = MessageDigest.getInstance("MD5").digest(AdbKeyCodec.encodePublicKeyBlob(publicKey))

        assertEquals(47, fingerprint.length)
        assertEquals(md5.joinToString(":") { "%02X".format(it) }, fingerprint)
    }

    @Test
    fun base64MatchesStandardEncoder() {
        for (length in 0..70) {
            val data = ByteArray(length) { (it * 37 + length).toByte() }
            assertEquals(Base64.getEncoder().encodeToString(data), AdbKeyCodec.base64(data))
        }
    }

    @Test
    fun signatureBlockUsesPkcs1Padding() {
        val block = AdbKeyCodec.pkcs1SignatureBlock(AdbKeyCodec.digestInfoForToken(token))

        assertEquals(256, block.size)
        assertEquals(0, block[0].toInt())
        assertEquals(1, block[1].toInt())
        assertTrue((2 until 220).all { block[it] == 0xFF.toByte() })
        assertEquals(0, block[220].toInt())
        assertArrayEquals(token, block.copyOfRange(236, 256))
    }
}
