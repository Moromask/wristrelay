package com.wristrelay.app.ble.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FragmentationTest {

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size) { (it % 251).toByte() }

    @Test
    fun single_fragment_when_small_message() {
        val message = randomBytes(100)
        val fragments = Fragmentation.split(sequence = 42L, message = message)

        assertEquals(1, fragments.size)

        val header = Fragmentation.parseHeader(fragments[0])
        assertNotNull(header)
        assertEquals(Fragmentation.MARKER_SINGLE, header!!.first)
        assertEquals(42L, header.second)
        assertEquals(0, header.third)

        val result = Fragmentation.accept(fragments[0])
        assertArrayEquals(message, result)
    }

    @Test
    fun single_fragment_at_exact_boundary() {
        val payloadMax = Fragmentation.MAX_FRAGMENT_TOTAL - Fragmentation.HEADER_SIZE
        val message = randomBytes(payloadMax)
        val fragments = Fragmentation.split(1L, message)

        assertEquals(1, fragments.size)
        assertArrayEquals(message, Fragmentation.accept(fragments[0]))
    }

    @Test
    fun multi_fragment_split_and_join() {
        val message = randomBytes(2000)
        val fragments = Fragmentation.split(sequence = 7L, message = message)

        assertEquals(5, fragments.size)

        val firstHeader = Fragmentation.parseHeader(fragments[0])
        assertEquals(Fragmentation.MARKER_CONTINUE, firstHeader!!.first)
        assertEquals(7L, firstHeader.second)
        assertEquals(0, firstHeader.third)

        val lastHeader = Fragmentation.parseHeader(fragments.last())
        assertEquals(Fragmentation.MARKER_LAST, lastHeader!!.first)
        assertEquals(4, lastHeader.third)

        for (i in 0 until fragments.size - 1) {
            assertNull("фрагмент $i ещё не последний", Fragmentation.accept(fragments[i]))
        }
        val result = Fragmentation.accept(fragments.last())
        assertArrayEquals(message, result)
    }

    @Test
    fun different_sequences_do_not_mix() {
        val msg1 = randomBytes(1200)
        val msg2 = randomBytes(1500)
        val f1 = Fragmentation.split(sequence = 100L, message = msg1)
        val f2 = Fragmentation.split(sequence = 200L, message = msg2)

        assertNull(Fragmentation.accept(f1[0]))
        assertNull(Fragmentation.accept(f2[0]))

        for (i in 1 until f1.size - 1) {
            assertNull(Fragmentation.accept(f1[i]))
        }
        val r1 = Fragmentation.accept(f1.last())
        assertArrayEquals(msg1, r1)

        for (i in 1 until f2.size - 1) {
            assertNull(Fragmentation.accept(f2[i]))
        }
        val r2 = Fragmentation.accept(f2.last())
        assertArrayEquals(msg2, r2)
    }

    @Test
    fun short_fragment_returns_null() {
        assertNull(Fragmentation.accept(ByteArray(5)))
    }

    @Test
    fun large_sequence_parses_correctly() {
        val seq = Long.MAX_VALUE - 1
        val fragments = Fragmentation.split(sequence = seq, message = randomBytes(600))
        val header = Fragmentation.parseHeader(fragments[0])
        assertNotNull(header)
        assertEquals(seq, header!!.second)
    }

    @Test
    fun qr_payload_roundtrip() {
        val message = "WRISTRELAY:1:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
            .toByteArray(Charsets.UTF_8)
        val fragments = Fragmentation.split(sequence = 1L, message = message)
        var result: ByteArray? = null
        for (f in fragments) {
            result = Fragmentation.accept(f)
        }
        assertNotNull(result)
        assertEquals(String(message, Charsets.UTF_8), String(result!!, Charsets.UTF_8))
    }
}
