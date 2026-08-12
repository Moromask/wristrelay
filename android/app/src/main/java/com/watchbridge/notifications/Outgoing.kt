package com.watchbridge.notifications

import android.util.Log
import com.watchbridge.ble.protocol.EnvelopeCodec
import com.watchbridge.proto.Bridge.MessageType
import com.watchbridge.proto.Bridge.Notification
import com.watchbridge.proto.Bridge.NotificationRemoved

/**
 * Мост «уведомление → часы».
 *
 * NotificationListener и BridgeService могут работать в одном процессе.
 * Если да — NotificationBridge (синглтон в приложении) отправляет сообщение
 * напрямую в GattServer через колбэк. Если нет — fallback через Intent.
 */
object Outgoing {

    private const val TAG = "Outgoing"
    private const val ACTION_FORWARD = "com.watchbridge.FORWARD"

    /** Регистрируется BridgeService при старте. */
    var activeForwarder: ((ByteArray) -> Unit)? = null
        private set

    fun setForwarder(f: (ByteArray) -> Unit) {
        activeForwarder = f
    }

    fun clearForwarder() {
        activeForwarder = null
    }

    fun sendNotification(notification: Notification) {
        val bytes = EnvelopeCodec.wrap(
            MessageType.NOTIFICATION,
            notification,
            sequence.getAndIncrement()
        )
        send(bytes)
    }

    fun sendRemoved(key: String, reason: Int) {
        val proto = NotificationRemoved.newBuilder()
            .setKey(key)
            .setReason(reason)
            .build()
        val bytes = EnvelopeCodec.wrap(
            MessageType.NOTIFICATION_REMOVED,
            proto,
            sequence.getAndIncrement()
        )
        send(bytes)
    }

    private fun send(bytes: ByteArray) {
        val forwarder = activeForwarder
        if (forwarder != null) {
            forwarder.invoke(bytes)
            return
        }
        Log.w(TAG, "нет активного форвардера — сообщение потеряно")
    }

    private val sequence = java.util.concurrent.atomic.AtomicLong(1)
}
