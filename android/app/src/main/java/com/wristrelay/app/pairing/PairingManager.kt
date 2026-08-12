package com.wristrelay.app.pairing

import android.util.Log
import com.wristrelay.app.proto.Bridge.PairingMessage
import com.wristrelay.app.proto.Bridge.PairingStep
import com.wristrelay.app.storage.SecureStorage
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PIN-based pairing over the encrypted BLE channel.
 *
 * Flow (see docs/PROTOCOL.md):
 *  1. Watch -> Phone  WATCH_HELLO(nonce=32B)
 *  2. Phone shows a 6-digit PIN in the UI
 *  3. Phone -> Watch  PHONE_PIN (notifies that PIN is generated)
 *  4. Watch -> Phone  WATCH_PIN(pin_hash = SHA-256(pin + nonce))
 *  5. Phone verifies, stores session key, replies PHONE_VERIFIED(success=true, session_key)
 *  6. Watch replies WATCH_VERIFIED -> fully paired
 */
class PairingManager(private val storage: SecureStorage) {

    interface Callback {
        fun onPinRequired(pin: String)
        fun onPaired(sessionKey: ByteArray)
        fun onPairingFailed(reason: String)
    }

    private var callback: Callback? = null
    private var currentNonce: ByteArray? = null
    private var currentPin: String? = null
    private var failureCount = 0

    fun setCallback(cb: Callback) {
        callback = cb
    }

    val isPaired: Boolean
        get() = storage.get(KEY_SESSION) != null

    fun reset() {
        currentNonce = null
        currentPin = null
        failureCount = 0
    }

    fun startPairing(): PairingMessage {
        // We (phone) generate the nonce or watch sends it. In our flow the watch
        // sends WATCH_HELLO first. This is called when the phone initiates.
        val pin = generatePin()
        currentPin = pin
        callback?.onPinRequired(pin)
        return PairingMessage.newBuilder()
            .setStep(PairingStep.PHONE_PIN)
            .build()
    }

    /** Обработка сообщения от часов. */
    fun handleWatchMessage(msg: PairingMessage): PairingMessage? {
        return when (msg.step) {
            PairingStep.WATCH_HELLO -> {
                val nonce = msg.nonce.toByteArray()
                if (nonce.size != 32) {
                    callback?.onPairingFailed("bad nonce size")
                    return null
                }
                currentNonce = nonce
                val pin = generatePin()
                currentPin = pin
                callback?.onPinRequired(pin)
                // reply: notify the watch a PIN is ready
                PairingMessage.newBuilder().setStep(PairingStep.PHONE_PIN).build()
            }
            PairingStep.WATCH_PIN -> {
                val nonce = currentNonce
                val pin = currentPin
                if (nonce == null || pin == null) {
                    callback?.onPairingFailed("pairing not started")
                    return null
                }
                val expected = sha256((pin + byteArrayToString(nonce)).toByteArray(Charsets.UTF_8))
                if (msg.pinHash.toByteArray().contentEquals(expected)) {
                    failureCount = 0
                    val sessionKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
                    storage.put(KEY_SESSION, android.util.Base64.encodeToString(sessionKey, android.util.Base64.NO_WRAP))
                    callback?.onPaired(sessionKey)
                    PairingMessage.newBuilder()
                        .setStep(PairingStep.PHONE_VERIFIED)
                        .setSuccess(true)
                        .setSessionKey(com.google.protobuf.ByteString.copyFrom(sessionKey))
                        .build()
                } else {
                    failureCount++
                    if (failureCount >= 3) {
                        reset()
                        callback?.onPairingFailed("too many attempts")
                        null
                    } else {
                        callback?.onPairingFailed("wrong pin")
                        null
                    }
                }
            }
            PairingStep.WATCH_VERIFIED -> {
                // watch confirmed end-to-end success
                PairingMessage.newBuilder().setStep(PairingStep.RESET).build()
            }
            PairingStep.RESET -> {
                reset()
                storage.remove(KEY_SESSION)
                null
            }
            else -> null
        }
    }

    private fun generatePin(): String {
        val rnd = SecureRandom()
        return String.format("%06d", rnd.nextInt(1_000_000))
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun byteArrayToString(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) sb.append((b.toInt() and 0xFF).toString(16).padStart(2, '0'))
        return sb.toString()
    }

    companion object {
        private const val TAG = "PairingManager"
        private const val KEY_SESSION = "pairing_session_key"
    }
}
