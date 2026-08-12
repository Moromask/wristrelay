package com.wristrelay.app.storage

/**
 * Простой key-value интерфейс для хранилища секретов.
 * Выделен, чтобы PairingManager можно было тестировать с in-memory fake.
 */
interface KeyValueStorage {
    fun put(key: String, value: String): Boolean
    fun get(key: String): String?
    fun remove(key: String)
    fun contains(key: String): Boolean
}
