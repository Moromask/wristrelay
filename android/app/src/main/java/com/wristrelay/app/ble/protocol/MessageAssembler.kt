package com.wristrelay.app.ble.protocol

import android.bluetooth.BluetoothDevice
import com.wristrelay.app.proto.Bridge.Envelope

/**
 * Собирает фрагменты в полные сообщения и передаёт Envelope дальше.
 * Потокобезопасен (ConcurrentHashMap).
 */
class MessageAssembler {

    interface Listener {
        fun onEnvelope(device: BluetoothDevice, characteristicUuid: String, envelope: Envelope)
    }

    private var listener: Listener? = null

    fun setListener(l: Listener) {
        listener = l
    }

    /** Принимает сырые байты, записанные в характеристику. */
    fun handle(device: BluetoothDevice, characteristicUuid: String, raw: ByteArray) {
        val message = Fragmentation.accept(raw) ?: return
        val envelope = EnvelopeCodec.parse(message) ?: return
        listener?.onEnvelope(device, characteristicUuid, envelope)
    }
}
