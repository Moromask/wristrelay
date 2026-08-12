package com.wristrelay.app.ble.protocol

import com.wristrelay.app.proto.Bridge.Envelope
import com.wristrelay.app.proto.Bridge.MessageType
import com.wristrelay.app.proto.Bridge.Notification
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EnvelopeCodecTest {

    @Test
    fun roundtrip_with_raw_payload() {
        val payload = ByteArray(16) { it.toByte() }
        val bytes = EnvelopeCodec.wrap(MessageType.HEALTH_SAMPLE, payload, sequence = 5L)

        val envelope = EnvelopeCodec.parse(bytes)
        assertNotNull(envelope)
        assertEquals(5L, envelope!!.sequence)
        assertEquals(MessageType.HEALTH_SAMPLE, envelope.type)
        assertArrayEquals(payload, envelope.payload.toByteArray())
    }

    @Test
    fun roundtrip_with_protobuf_message() {
        val notification = Notification.newBuilder()
            .setPackageName("com.example")
            .setKey("k1")
            .setTitle("Title")
            .setText("Body")
            .setAppName("Example")
            .setPostedAtMs(12345)
            .build()

        val bytes = EnvelopeCodec.wrap(MessageType.NOTIFICATION, notification, sequence = 9L)
        val envelope = EnvelopeCodec.parse(bytes)

        assertNotNull(envelope)
        assertEquals(MessageType.NOTIFICATION, envelope!!.type)

        val parsed = Notification.parseFrom(envelope.payload)
        assertEquals("com.example", parsed.packageName)
        assertEquals("k1", parsed.key)
        assertEquals("Title", parsed.title)
        assertEquals("Body", parsed.text)
        assertEquals("Example", parsed.appName)
        assertEquals(12345L, parsed.postedAtMs)
    }

    @Test
    fun parse_garbage_returns_null() {
        val garbage = ByteArray(32) { 0xFF.toByte() }
        assertNull(EnvelopeCodec.parse(garbage))
    }

    @Test
    fun parse_empty_returns_default_envelope() {
        // protobuf-lite: parseFrom(пусто) -> пустой Envelope, а не исключение.
        // parse() оборачивает в try и возвращает объект.
        val envelope = EnvelopeCodec.parse(ByteArray(0))
        assertNotNull(envelope)
        assertEquals(0L, envelope!!.sequence)
        assertEquals(com.wristrelay.app.proto.Bridge.MessageType.MESSAGE_UNKNOWN, envelope.type)
    }

    @Test
    fun sequence_preserved_on_roundtrip() {
        val bytes = EnvelopeCodec.wrap(
            MessageType.PING, ByteArray(0), sequence = Long.MAX_VALUE
        )
        val envelope = EnvelopeCodec.parse(bytes)
        assertEquals(Long.MAX_VALUE, envelope!!.sequence)
    }
}
