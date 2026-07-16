package io.github.ycfeng.ocdeck.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.ycfeng.ocdeck.core.sound.OpenCodeSoundCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")

class AppSettingsStore(context: Context) {
    private val dataStore = context.applicationContext.appSettingsDataStore

    val colorSchemePreference: Flow<AppColorSchemePreference> = dataStore.data
        .map { preferences -> AppColorSchemePreference.fromStorageValue(preferences[colorSchemePreferenceKey]) }
        .catch { emit(AppColorSchemePreference.System) }

    val languagePreference: Flow<AppLanguagePreference> = dataStore.data
        .map { preferences -> AppLanguagePreference.fromStorageValue(preferences[languagePreferenceKey]) }
        .catch { emit(AppLanguagePreference.System) }

    val notificationSettings: Flow<AppNotificationSettings> = dataStore.data
        .map { preferences ->
            AppNotificationSettings(
                agentEnabled = preferences[notificationAgentEnabledKey] ?: true,
                permissionsEnabled = preferences[notificationPermissionsEnabledKey] ?: true,
                errorsEnabled = preferences[notificationErrorsEnabledKey] ?: false,
            )
        }
        .catch { emit(AppNotificationSettings()) }

    val soundSettings: Flow<AppSoundSettings> = dataStore.data
        .map { preferences ->
            AppSoundSettings(
                agentEnabled = preferences[soundAgentEnabledKey] ?: true,
                agent = OpenCodeSoundCatalog.soundIdOrDefault(
                    preferences[soundAgentKey],
                    OpenCodeSoundCatalog.DefaultAgentSoundId,
                ),
                permissionsEnabled = preferences[soundPermissionsEnabledKey] ?: true,
                permissions = OpenCodeSoundCatalog.soundIdOrDefault(
                    preferences[soundPermissionsKey],
                    OpenCodeSoundCatalog.DefaultPermissionsSoundId,
                ),
                errorsEnabled = preferences[soundErrorsEnabledKey] ?: true,
                errors = OpenCodeSoundCatalog.soundIdOrDefault(
                    preferences[soundErrorsKey],
                    OpenCodeSoundCatalog.DefaultErrorsSoundId,
                ),
            )
        }
        .catch { emit(AppSoundSettings()) }

    suspend fun setColorSchemePreference(preference: AppColorSchemePreference) {
        dataStore.edit { preferences ->
            preferences[colorSchemePreferenceKey] = preference.storageValue
        }
    }

    suspend fun setLanguagePreference(preference: AppLanguagePreference) {
        dataStore.edit { preferences ->
            preferences[languagePreferenceKey] = preference.storageValue
        }
    }

    suspend fun setAgentNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[notificationAgentEnabledKey] = enabled
        }
    }

    suspend fun setPermissionNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[notificationPermissionsEnabledKey] = enabled
        }
    }

    suspend fun setErrorNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[notificationErrorsEnabledKey] = enabled
        }
    }

    suspend fun setAgentSound(soundId: String) {
        setSoundPreference(soundAgentEnabledKey, soundAgentKey, soundId)
    }

    suspend fun setPermissionSound(soundId: String) {
        setSoundPreference(soundPermissionsEnabledKey, soundPermissionsKey, soundId)
    }

    suspend fun setErrorSound(soundId: String) {
        setSoundPreference(soundErrorsEnabledKey, soundErrorsKey, soundId)
    }

    private suspend fun setSoundPreference(
        enabledKey: androidx.datastore.preferences.core.Preferences.Key<Boolean>,
        soundKey: androidx.datastore.preferences.core.Preferences.Key<String>,
        soundId: String,
    ) {
        dataStore.edit { preferences ->
            if (OpenCodeSoundCatalog.isNone(soundId)) {
                preferences[enabledKey] = false
                return@edit
            }
            val option = OpenCodeSoundCatalog.soundOptionForId(soundId) ?: return@edit
            preferences[enabledKey] = true
            preferences[soundKey] = option.id
        }
    }

    private companion object {
        val colorSchemePreferenceKey = stringPreferencesKey("color_scheme")
        val languagePreferenceKey = stringPreferencesKey("language")
        val notificationAgentEnabledKey = booleanPreferencesKey("notification_agent_enabled")
        val notificationPermissionsEnabledKey = booleanPreferencesKey("notification_permissions_enabled")
        val notificationErrorsEnabledKey = booleanPreferencesKey("notification_errors_enabled")
        val soundAgentEnabledKey = booleanPreferencesKey("sound_agent_enabled")
        val soundAgentKey = stringPreferencesKey("sound_agent")
        val soundPermissionsEnabledKey = booleanPreferencesKey("sound_permissions_enabled")
        val soundPermissionsKey = stringPreferencesKey("sound_permissions")
        val soundErrorsEnabledKey = booleanPreferencesKey("sound_errors_enabled")
        val soundErrorsKey = stringPreferencesKey("sound_errors")
    }
}

data class AppNotificationSettings(
    val agentEnabled: Boolean = true,
    val permissionsEnabled: Boolean = true,
    val errorsEnabled: Boolean = false,
)

data class AppSoundSettings(
    val agentEnabled: Boolean = true,
    val agent: String = OpenCodeSoundCatalog.DefaultAgentSoundId,
    val permissionsEnabled: Boolean = true,
    val permissions: String = OpenCodeSoundCatalog.DefaultPermissionsSoundId,
    val errorsEnabled: Boolean = true,
    val errors: String = OpenCodeSoundCatalog.DefaultErrorsSoundId,
)

enum class AppColorSchemePreference(val storageValue: String) {
    System("system"),
    Light("light"),
    Dark("dark"),
    ;

    companion object {
        fun fromStorageValue(value: String?): AppColorSchemePreference =
            entries.firstOrNull { it.storageValue == value } ?: System
    }
}

enum class AppLanguagePreference(val storageValue: String) {
    System("system"),
    English("en"),
    SimplifiedChinese("zh-Hans"),
    ;

    companion object {
        fun fromStorageValue(value: String?): AppLanguagePreference =
            entries.firstOrNull { it.storageValue == value } ?: System
    }
}
