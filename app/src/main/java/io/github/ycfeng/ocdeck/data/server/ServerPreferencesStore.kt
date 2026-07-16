package io.github.ycfeng.ocdeck.data.server

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.serverDataStore by preferencesDataStore(name = "server_preferences")

interface ServerConfigStore {
    val servers: Flow<List<ServerConfig>>

    suspend fun getServers(): List<ServerConfig>

    fun observeComposerModelPreference(serverId: String): Flow<ServerComposerModelPreference?>

    suspend fun getComposerModelPreference(serverId: String): ServerComposerModelPreference?

    suspend fun getHiddenModelPreferences(serverId: String): List<ServerHiddenModelPreference>

    suspend fun upsertServer(server: ServerConfig)

    suspend fun reorderServers(orderedIds: List<String>)

    suspend fun migrateLegacyDefaultServer()

    suspend fun setComposerModelPreference(preference: ServerComposerModelPreference)

    suspend fun setModelHidden(serverId: String, providerId: String, modelId: String, hidden: Boolean)

    suspend fun deleteServer(serverId: String)
}

class ServerPreferencesStore(
    context: Context,
    private val json: Json,
) : ServerConfigStore {
    private val dataStore = context.applicationContext.serverDataStore

    override val servers: Flow<List<ServerConfig>> = dataStore.data
        .map { preferences ->
            preferences[serversKey]?.let { json.decodeFromString<List<ServerConfig>>(it) }.orEmpty()
        }
        .catch { emit(emptyList()) }

    val currentServerId: Flow<String?> = dataStore.data
        .map { preferences -> preferences[currentServerIdKey] }
        .catch { emit(null) }

    override suspend fun getServers(): List<ServerConfig> = dataStore.data
        .map { preferences ->
            preferences[serversKey]?.let { json.decodeFromString<List<ServerConfig>>(it) }.orEmpty()
        }
        .first()

    override fun observeComposerModelPreference(serverId: String): Flow<ServerComposerModelPreference?> = dataStore.data
        .map { preferences ->
            decodeComposerModelPreferences(preferences[composerModelPreferencesKey])
                .firstOrNull { it.serverId == serverId }
        }
        .catch { emit(null) }

    override suspend fun getComposerModelPreference(serverId: String): ServerComposerModelPreference? = dataStore.data
        .map { preferences ->
            decodeComposerModelPreferences(preferences[composerModelPreferencesKey])
                .firstOrNull { it.serverId == serverId }
        }
        .catch { emit(null) }
        .first()

    override suspend fun getHiddenModelPreferences(serverId: String): List<ServerHiddenModelPreference> = dataStore.data
        .map { preferences ->
            decodeHiddenModelPreferences(preferences[hiddenModelPreferencesKey])
                .filter { it.serverId == serverId }
        }
        .catch { emit(emptyList()) }
        .first()

    override suspend fun upsertServer(server: ServerConfig) {
        dataStore.edit { preferences ->
            val current = preferences[serversKey]
                ?.let { json.decodeFromString<List<ServerConfig>>(it) }
                .orEmpty()
            val next = if (current.any { it.id == server.id }) {
                current.map { if (it.id == server.id) server else it }
            } else {
                current + server
            }
            preferences[serversKey] = json.encodeToString(next)
            preferences[currentServerIdKey] = server.id
        }
    }

    override suspend fun reorderServers(orderedIds: List<String>) {
        dataStore.edit { preferences ->
            val current = preferences[serversKey]
                ?.let { json.decodeFromString<List<ServerConfig>>(it) }
                .orEmpty()
            val next = reorderServersByIds(current, orderedIds)
            if (next != current) {
                preferences[serversKey] = json.encodeToString(next)
            }
        }
    }

    override suspend fun migrateLegacyDefaultServer() {
        dataStore.edit { preferences ->
            if (preferences[legacyDefaultServerMigrationCompletedKey] == true) {
                return@edit
            }
            val current = preferences[serversKey]
                ?.let { json.decodeFromString<List<ServerConfig>>(it) }
                .orEmpty()
            val next = removeUnmodifiedLegacyDefaultServer(current)
            if (next != current) {
                preferences[serversKey] = json.encodeToString(next)
                if (preferences[currentServerIdKey] == LEGACY_DEFAULT_SERVER_ID) {
                    preferences.remove(currentServerIdKey)
                }
                val modelPreferences = decodeComposerModelPreferences(preferences[composerModelPreferencesKey])
                    .filterNot { it.serverId == LEGACY_DEFAULT_SERVER_ID }
                if (modelPreferences.isEmpty()) {
                    preferences.remove(composerModelPreferencesKey)
                } else {
                    preferences[composerModelPreferencesKey] = json.encodeToString(modelPreferences)
                }
                val hiddenModelPreferences = decodeHiddenModelPreferences(preferences[hiddenModelPreferencesKey])
                    .filterNot { it.serverId == LEGACY_DEFAULT_SERVER_ID }
                if (hiddenModelPreferences.isEmpty()) {
                    preferences.remove(hiddenModelPreferencesKey)
                } else {
                    preferences[hiddenModelPreferencesKey] = json.encodeToString(hiddenModelPreferences)
                }
            }
            preferences.remove(defaultServerInitializedKey)
            preferences[legacyDefaultServerMigrationCompletedKey] = true
        }
    }

    suspend fun setCurrentServer(serverId: String) {
        dataStore.edit { preferences ->
            preferences[currentServerIdKey] = serverId
        }
    }

    override suspend fun setComposerModelPreference(preference: ServerComposerModelPreference) {
        dataStore.edit { preferences ->
            val current = decodeComposerModelPreferences(preferences[composerModelPreferencesKey])
            val next = current.filterNot { it.serverId == preference.serverId } + preference
            preferences[composerModelPreferencesKey] = json.encodeToString(next)
        }
    }

    override suspend fun setModelHidden(serverId: String, providerId: String, modelId: String, hidden: Boolean) {
        dataStore.edit { preferences ->
            val current = decodeHiddenModelPreferences(preferences[hiddenModelPreferencesKey])
            val withoutModel = current.filterNot {
                it.serverId == serverId && it.providerId == providerId && it.modelId == modelId
            }
            val next = if (hidden) {
                withoutModel + ServerHiddenModelPreference(serverId, providerId, modelId)
            } else {
                withoutModel
            }
            if (next.isEmpty()) {
                preferences.remove(hiddenModelPreferencesKey)
            } else {
                preferences[hiddenModelPreferencesKey] = json.encodeToString(next)
            }
        }
    }

    override suspend fun deleteServer(serverId: String) {
        dataStore.edit { preferences ->
            val current = preferences[serversKey]
                ?.let { json.decodeFromString<List<ServerConfig>>(it) }
                .orEmpty()
            preferences[serversKey] = json.encodeToString(current.filterNot { it.id == serverId })
            if (preferences[currentServerIdKey] == serverId) {
                preferences.remove(currentServerIdKey)
            }
            val modelPreferences = decodeComposerModelPreferences(preferences[composerModelPreferencesKey])
                .filterNot { it.serverId == serverId }
            if (modelPreferences.isEmpty()) {
                preferences.remove(composerModelPreferencesKey)
            } else {
                preferences[composerModelPreferencesKey] = json.encodeToString(modelPreferences)
            }
            val hiddenModelPreferences = decodeHiddenModelPreferences(preferences[hiddenModelPreferencesKey])
                .filterNot { it.serverId == serverId }
            if (hiddenModelPreferences.isEmpty()) {
                preferences.remove(hiddenModelPreferencesKey)
            } else {
                preferences[hiddenModelPreferencesKey] = json.encodeToString(hiddenModelPreferences)
            }
        }
    }

    private fun decodeComposerModelPreferences(raw: String?): List<ServerComposerModelPreference> = raw
        ?.let { runCatching { json.decodeFromString<List<ServerComposerModelPreference>>(it) }.getOrDefault(emptyList()) }
        .orEmpty()

    private fun decodeHiddenModelPreferences(raw: String?): List<ServerHiddenModelPreference> = raw
        ?.let { runCatching { json.decodeFromString<List<ServerHiddenModelPreference>>(it) }.getOrDefault(emptyList()) }
        .orEmpty()

    private companion object {
        val serversKey = stringPreferencesKey("servers_json")
        val currentServerIdKey = stringPreferencesKey("current_server_id")
        val composerModelPreferencesKey = stringPreferencesKey("composer_model_preferences_json")
        val hiddenModelPreferencesKey = stringPreferencesKey("hidden_model_preferences_json")
        val defaultServerInitializedKey = booleanPreferencesKey("default_server_initialized")
        val legacyDefaultServerMigrationCompletedKey = booleanPreferencesKey("legacy_default_server_migration_completed")
    }
}

internal fun removeUnmodifiedLegacyDefaultServer(servers: List<ServerConfig>): List<ServerConfig> =
    servers.filterNot { server ->
        server.id == LEGACY_DEFAULT_SERVER_ID &&
            server.name == LEGACY_DEFAULT_SERVER_NAME &&
            server.baseUrl in LEGACY_DEFAULT_SERVER_BASE_URLS &&
            server.username == null &&
            server.passwordKey == null &&
            server.sshTunnel == null &&
            server.frpcStcpVisitor == null
    }

private const val LEGACY_DEFAULT_SERVER_ID = "local"
private const val LEGACY_DEFAULT_SERVER_NAME = "Localhost"
private val LEGACY_DEFAULT_SERVER_BASE_URLS = setOf(
    "http://localhost:4096",
    "http://127.0.0.1:4096",
)

internal fun reorderServersByIds(
    current: List<ServerConfig>,
    orderedIds: List<String>,
): List<ServerConfig> {
    val serversById = current.associateBy { it.id }
    val seenIds = linkedSetOf<String>()
    val orderedServers = orderedIds.mapNotNull { id ->
        if (seenIds.add(id)) serversById[id] else null
    }
    return orderedServers + current.filterNot { it.id in seenIds }
}
