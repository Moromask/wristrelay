package com.wristrelay.app.pairing

import android.util.Log
import com.wristrelay.app.proto.Bridge.PairingMessage
import com.wristrelay.app.proto.Bridge.PairingStep
import com.wristrelay.app.storage.KeyValueStorage
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Пэйринг по защищённому BLE-каналу.
 *
 * Два потока (см. docs/PROTOCOL.md):
 *  A. QR: Phone получает nonce из QR (с экрана часов), сверяет его в WATCH_HELLO.
 *  B. PIN: Phone генерирует 6-значный PIN, watch присылает pin_hash.
 *
 * Поток A (QR):
 *  1. Phone: acceptQrNonce(nonce) — из QR-скана.
 *  2. Watch -> Phone WATCH_HELLO(nonce)
 *  3. Phone сверяет с nonce из QR -> совпало -> PHONE_VERIFIED(success, session_key)
 *  4. Watch -> WATCH_VERIFIED
 */
class PairingManager(private val storage: KeyValueStorage) {

    interface Callback {
        fun onPinRequired(pin: String)
        fun onPaired(sessionKey: ByteArray)
        fun onPairingFailed(reason: String)
    }

    private var callback: Callback? = null
    private var currentNonce: ByteArray? = null
    private var currentPin: String? = null
    private var qrNonce: ByteArray? = null
    private var qrNonceTimestamp: Long = 0
    private var failureCount = 0

    fun setCallback(cb: Callback) {
        callback = cb
    }

    val isPaired: Boolean
        get() = storage.get(KEY_SESSION) != null

    fun reset() {
        currentNonce = null
        currentPin = null
        qrNonce = null
        failureCount = 0
    }

    /**
     * Телефон получил nonce из QR-кода (сканирование экрана часов).
     * Ожидается, что watch пришлёт его в WATCH_HELLO в течение QR_TTL_MS.
     */
    fun acceptQrNonce(nonce: ByteArray) {
        if (nonce.size != 32) {
            Log.w(TAG, "QR nonce bad size: ${nonce.size}")
            return
        }
        qrNonce = nonce
        qrNonceTimestamp = System.currentTimeMillis()
        Log.d(TAG, "QR nonce принят")
    }

    /** Обработка сообщения от часов. */
    fun handleWatchMessage(msg: PairingMessage): PairingMessage? {
        return when (msg.step) {
            PairingStep.WATCH_HELLO -> handleHello(msg)
            PairingStep.WATCH_PIN -> handlePin(msg)
            PairingStep.WATCH_VERIFIED -> {
                // watch подтвердил успех end-to-end
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

    private fun handleHello(msg: PairingMessage): PairingMessage? {
        val nonce = msg.nonce.toByteArray()
        if (nonce.size != 32) {
            callback?.onPairingFailed("bad nonce size")
            return null
        }
        currentNonce = nonce

        // QR-путь: сверяем nonce с тем, что пришло из QR
        val qr = qrNonce
        val qrFresh = qr != null &&
            (System.currentTimeMillis() - qrNonceTimestamp) <= QR_TTL_MS
        if (qrFresh && qr != null && qr.contentEquals(nonce)) {
            qrNonce = null
            return completePairing()
        }

        // иначе — PIN-путь
        val pin = generatePin()
        currentPin = pin
        callback?.onPinRequired(pin)
        return PairingMessage.newBuilder().setStep(PairingStep.PHONE_PIN).build()
    }

    private fun handlePin(msg: PairingMessage): PairingMessage? {
        val nonce = currentNonce
        val pin = currentPin
        if (nonce == null || pin == null) {
            callback?.onPairingFailed("pairing not started")
            return null
        }
        val expected = sha256((pin + byteArrayToString(nonce)).toByteArray(Charsets.UTF_8))
        return if (msg.pinHash.toByteArray().contentEquals(expected)) {
            failureCount = 0
            completePairing()
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

    private fun completePairing(): PairingMessage {
        val sessionKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        storage.put(KEY_SESSION, java.util.Base64.getEncoder().encodeToString(sessionKey))
        callback?.onPaired(sessionKey)
        return PairingMessage.newBuilder()
            .setStep(PairingStep.PHONE_VERIFIED)
            .setSuccess(true)
            .setSessionKey(com.google.protobuf.ByteString.copyFrom(sessionKey))
            .build()
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
        private const val QR_TTL_MS = 2 * 60 * 1000L
    }
}
