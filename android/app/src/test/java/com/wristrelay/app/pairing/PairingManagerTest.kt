package com.wristrelay.app.pairing

import com.wristrelay.app.proto.Bridge.PairingMessage
import com.wristrelay.app.proto.Bridge.PairingStep
import com.wristrelay.app.storage.KeyValueStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom

/** In-memory реализация KeyValueStorage для тестов. */
private class FakeStorage : KeyValueStorage {
    private val map = HashMap<String, String>()
    override fun put(key: String, value: String): Boolean { map[key] = value; return true }
    override fun get(key: String): String? = map[key]
    override fun remove(key: String) { map.remove(key) }
    override fun contains(key: String): Boolean = map.containsKey(key)
}

class PairingManagerTest {

    private fun randomNonce(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun helloMessage(nonce: ByteArray): PairingMessage =
        PairingMessage.newBuilder()
            .setStep(PairingStep.WATCH_HELLO)
            .setNonce(com.google.protobuf.ByteString.copyFrom(nonce))
            .build()

    @Test
    fun qrFlow_matchingNonce_returnsVerified() {
        val storage = FakeStorage()
        val manager = PairingManager(storage)
        var paired: ByteArray? = null
        manager.setCallback(object : PairingManager.Callback {
            override fun onPinRequired(pin: String) {}
            override fun onPaired(sessionKey: ByteArray) { paired = sessionKey }
            override fun onPairingFailed(reason: String) {}
        })

        val nonce = randomNonce()
        manager.acceptQrNonce(nonce)

        val reply = manager.handleWatchMessage(helloMessage(nonce))

        assertNotNull(reply)
        assertEquals(PairingStep.PHONE_VERIFIED, reply!!.step)
        assertTrue(reply.success)
        assertNotNull(reply.sessionKey)
        assertNotNull("onPaired должен вызваться", paired)
        assertTrue("session key должен сохраниться", storage.contains("pairing_session_key"))
    }

    @Test
    fun qrFlow_mismatchedNonce_fallsBackToPin() {
        val manager = PairingManager(FakeStorage())
        var pinShown: String? = null
        manager.setCallback(object : PairingManager.Callback {
            override fun onPinRequired(pin: String) { pinShown = pin }
            override fun onPaired(sessionKey: ByteArray) {}
            override fun onPairingFailed(reason: String) {}
        })

        manager.acceptQrNonce(randomNonce())
        val reply = manager.handleWatchMessage(helloMessage(randomNonce()))

        assertNotNull(reply)
        assertEquals(PairingStep.PHONE_PIN, reply!!.step)
        assertNotNull("должен показываться PIN", pinShown)
    }

    @Test
    fun pinFlow_correctHash_returnsVerified() {
        val manager = PairingManager(FakeStorage())
        var pinShown: String? = null
        manager.setCallback(object : PairingManager.Callback {
            override fun onPinRequired(pin: String) { pinShown = pin }
            override fun onPaired(sessionKey: ByteArray) {}
            override fun onPairingFailed(reason: String) {}
        })

        val nonce = randomNonce()
        val helloReply = manager.handleWatchMessage(helloMessage(nonce))
        assertEquals(PairingStep.PHONE_PIN, helloReply!!.step)
        val pin = pinShown ?: error("PIN не показан")

        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest((pin + nonce.toHexString()).toByteArray(Charsets.UTF_8))
        val pinMsg = PairingMessage.newBuilder()
            .setStep(PairingStep.WATCH_PIN)
            .setPinHash(com.google.protobuf.ByteString.copyFrom(expectedHash))
            .build()

        val reply = manager.handleWatchMessage(pinMsg)
        assertNotNull(reply)
        assertEquals(PairingStep.PHONE_VERIFIED, reply!!.step)
        assertTrue(reply.success)
    }

    @Test
    fun pinFlow_wrongHash_rejected() {
        val manager = PairingManager(FakeStorage())
        var failures = 0
        manager.setCallback(object : PairingManager.Callback {
            override fun onPinRequired(pin: String) {}
            override fun onPaired(sessionKey: ByteArray) {}
            override fun onPairingFailed(reason: String) { failures++ }
        })

        manager.handleWatchMessage(helloMessage(randomNonce()))

        val wrongPin = PairingMessage.newBuilder()
            .setStep(PairingStep.WATCH_PIN)
            .setPinHash(com.google.protobuf.ByteString.copyFrom(ByteArray(32) { 0x01 }))
            .build()

        val reply = manager.handleWatchMessage(wrongPin)
        assertNull("неверный PIN -> нет ответа", reply)
        assertEquals(1, failures)
        assertFalse("после одной ошибки не спарены", manager.isPaired)
    }

    @Test
    fun qrFlow_afterSuccess_isPaired() {
        val storage = FakeStorage()
        val manager = PairingManager(storage)
        assertFalse(manager.isPaired)

        val nonce = randomNonce()
        manager.acceptQrNonce(nonce)
        manager.handleWatchMessage(helloMessage(nonce))

        assertTrue(manager.isPaired)
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}
