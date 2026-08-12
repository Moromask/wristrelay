package com.watchbridge.ble

import java.util.UUID

/**
 * Фиксированные UUID сервисов и характеристик.
 * Должны совпадать со спецификацией docs/PROTOCOL.md и watchOS-клиентом.
 */
object BridgeUuids {
    private const val BASE = "-C4D1-4A7E-9B3A-9A8E1F2A3B4C"

    fun uuid(short: String): UUID = UUID.fromString("0000$short$BASE")

    // Службы
    val SERVICE_MAIN = uuid("0001")
    val SERVICE_PAIRING = uuid("0002")

    // Характеристики службы Main
    val CHAR_STATUS = uuid("0101")
    val CHAR_COMMAND = uuid("0102")
    val CHAR_NOTIFICATION = uuid("0103")
    val CHAR_HEALTH = uuid("0104")
    val CHAR_PAIRING = uuid("0105")

    // Характеристики службы Pairing
    val CHAR_PAIRING_REQUEST = uuid("0201")
    val CHAR_PAIRING_RESPONSE = uuid("0202")

    val ALL_CHARACTERISTICS: List<UUID> = listOf(
        CHAR_STATUS, CHAR_COMMAND, CHAR_NOTIFICATION, CHAR_HEALTH, CHAR_PAIRING
    )
}
