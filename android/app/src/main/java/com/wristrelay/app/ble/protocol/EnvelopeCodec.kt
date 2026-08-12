package com.wristrelay.app.ble.protocol

import com.wristrelay.app.proto.Bridge.Envelope
import com.wristrelay.app.proto.Bridge.MessageType

/**
 * Сериализация/десериализация Envelope.
 * Дополнительная обвязка не нужна — protobuf уже даёт компактные байты.
 */
object EnvelopeCodec {

    fun wrap(type: MessageType, payload: ByteArray, sequence: Long): ByteArray =
        Envelope.newBuilder()
            .setSequence(sequence)
            .setType(type)
            .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
            .build()
            .toByteArray()

    fun wrap(type: MessageType, message: com.google.protobuf.MessageLite, sequence: Long): ByteArray =
        wrap(type, message.toByteArray(), sequence)

    fun wrap(type: MessageType, builder: com.google.protobuf.MessageLite.Builder, sequence: Long): ByteArray =
        wrap(type, builder.build().toByteArray(), sequence)

    fun parse(data: ByteArray): Envelope? = try {
        Envelope.parseFrom(data)
    } catch (e: Exception) {
        null
    }
}
