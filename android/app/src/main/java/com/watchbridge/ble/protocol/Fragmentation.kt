package com.watchbridge.ble.protocol

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Фрагментация сообщений для BLE (согласовано с watchOS, см. docs/PROTOCOL.md).
 *
 * Формат каждого фрагмента (всего 11 байт заголовка):
 *   [marker:1][sequence:8 big-endian][index:2 big-endian][data]
 *
 * marker:
 *   0x00 — полное сообщение (data = все байты Envelope)
 *   0x01 — продолжение (не последний)
 *   0x02 — последний фрагмент
 *
 * sequence — идентификатор сообщения (совпадает с Envelope.sequence).
 * index — порядковый номер фрагмента.
 */
object Fragmentation {

    private const val TAG = "Fragmentation"
    const val HEADER_SIZE = 11
    const val MAX_FRAGMENT_TOTAL = 500 // запас под ATT-заголовки при MTU 512

    const val MARKER_SINGLE: Byte = 0x00
    const val MARKER_CONTINUE: Byte = 0x01
    const val MARKER_LAST: Byte = 0x02

    private val pending = ConcurrentHashMap<String, MutableList<ByteArray>>()

    /** Разбивает Envelope-байты на фрагменты с общим sequence. */
    fun split(sequence: Long, message: ByteArray): List<ByteArray> {
        val payloadMax = MAX_FRAGMENT_TOTAL - HEADER_SIZE
        if (message.size <= payloadMax) {
            return listOf(build(MARKER_SINGLE, sequence, 0, message))
        }
        val result = mutableListOf<ByteArray>()
        var offset = 0
        var index = 0
        while (offset < message.size) {
            val isLast = offset + payloadMax >= message.size
            val marker = if (isLast) MARKER_LAST else MARKER_CONTINUE
            val chunk = message.copyOfRange(offset, minOf(offset + payloadMax, message.size))
            result.add(build(marker, sequence, index, chunk))
            offset += chunk.size
            index++
        }
        return result
    }

    private fun build(marker: Byte, sequence: Long, index: Int, chunk: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE + chunk.size)
        out[0] = marker
        putLong(out, 1, sequence)
        out[9] = ((index shr 8) and 0xFF).toByte()
        out[10] = (index and 0xFF).toByte()
        System.arraycopy(chunk, 0, out, HEADER_SIZE, chunk.size)
        return out
    }

    /** Принимает фрагмент, возвращает полное сообщение, когда набор собран. */
    fun accept(fragment: ByteArray): ByteArray? {
        if (fragment.size < HEADER_SIZE) {
            Log.w(TAG, "короткий фрагмент: ${fragment.size}")
            return null
        }
        val marker = fragment[0]
        val sequence = bytesToLong(fragment, 1)
        val index = ((fragment[9].toInt() and 0xFF) shl 8) or (fragment[10].toInt() and 0xFF)
        val data = fragment.copyOfRange(HEADER_SIZE, fragment.size)
        val key = sequence.toString()

        when (marker) {
            MARKER_SINGLE -> return data
            MARKER_CONTINUE, MARKER_LAST -> {
                val list = pending.getOrPut(key) { mutableListOf() }
                while (list.size <= index) list.add(ByteArray(0))
                list[index] = data
                if (marker == MARKER_LAST) {
                    pending.remove(key)
                    val total = list.fold(0) { acc, b -> acc + b.size }
                    val out = ByteArray(total)
                    var off = 0
                    for (part in list) {
                        System.arraycopy(part, 0, out, off, part.size)
                        off += part.size
                    }
                    return out
                }
            }
        }
        return null
    }

    fun parseHeader(fragment: ByteArray): Triple<Byte, Long, Int>? {
        if (fragment.size < HEADER_SIZE) return null
        return Triple(
            fragment[0],
            bytesToLong(fragment, 1),
            ((fragment[9].toInt() and 0xFF) shl 8) or (fragment[10].toInt() and 0xFF)
        )
    }

    private fun putLong(out: ByteArray, offset: Int, value: Long) {
        var v = value
        for (i in 0 until 8) {
            out[offset + i] = ((v shr (56 - i * 8)) and 0xFF).toByte()
        }
    }

    private fun bytesToLong(data: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return result
    }
}
