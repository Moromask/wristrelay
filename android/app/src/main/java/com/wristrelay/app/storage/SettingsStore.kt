package com.wristrelay.app.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "wristrelay_settings")

/**
 * App settings persisted via DataStore.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val SERVICE_ENABLED = booleanPreferencesKey("service_enabled")
        val NOTIFICATION_SYNC_ENABLED = booleanPreferencesKey("notification_sync_enabled")
        val HEALTH_SYNC_ENABLED = booleanPreferencesKey("health_sync_enabled")
        val SYNCED_APP_PACKAGES = stringSetPreferencesKey("synced_app_packages")
        val PAIRED = booleanPreferencesKey("paired")
    }

    val serviceEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.SERVICE_ENABLED] ?: false }
    val notificationSyncEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.NOTIFICATION_SYNC_ENABLED] ?: true }
    val healthSyncEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.HEALTH_SYNC_ENABLED] ?: false }
    val paired: Flow<Boolean> = context.dataStore.data.map { it[Keys.PAIRED] ?: false }
    val syncedAppPackages: Flow<Set<String>> =
        context.dataStore.data.map { it[Keys.SYNCED_APP_PACKAGES] ?: emptySet() }

    suspend fun setServiceEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.SERVICE_ENABLED] = value }
    }

    suspend fun setNotificationSyncEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFICATION_SYNC_ENABLED] = value }
    }

    suspend fun setHealthSyncEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.HEALTH_SYNC_ENABLED] = value }
    }

    suspend fun setSyncedAppPackages(packages: Set<String>) {
        context.dataStore.edit { it[Keys.SYNCED_APP_PACKAGES] = packages }
    }

    suspend fun setPaired(value: Boolean) {
        context.dataStore.edit { it[Keys.PAIRED] = value }
    }
}
