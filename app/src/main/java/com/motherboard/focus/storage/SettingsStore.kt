package com.motherboard.focus.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "intervention_settings")

class SettingsStore(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val settings: Flow<InterventionSettings> = dataStore.data.map { preferences ->
        InterventionSettings(
            sessionLimit = preferences[Keys.SessionLimit] ?: 10,
            cooldownDurationMillis = preferences[Keys.CooldownDurationMillis] ?: 5 * 60 * 1000L,
            warningDurationMillis = preferences[Keys.WarningDurationMillis] ?: 2000L,
            eventDebounceMillis = preferences[Keys.EventDebounceMillis] ?: 1000L,
            youtubeShortsEnabled = preferences[Keys.YoutubeShortsEnabled] ?: true,
            blockingEnabled = preferences[Keys.BlockingEnabled] ?: true,
            shortsBlockedToday = preferences[Keys.ShortsBlockedToday] ?: 0,
            cooldownsTriggeredToday = preferences[Keys.CooldownsTriggeredToday] ?: 0,
            debugLogging = preferences[Keys.DebugLogging] ?: false,
            currentSessionCount = preferences[Keys.CurrentSessionCount] ?: 0,
        )
    }

    suspend fun save(settings: InterventionSettings) {
        dataStore.edit { preferences ->
            preferences[Keys.SessionLimit] = settings.sessionLimit
            preferences[Keys.CooldownDurationMillis] = settings.cooldownDurationMillis
            preferences[Keys.WarningDurationMillis] = settings.warningDurationMillis
            preferences[Keys.EventDebounceMillis] = settings.eventDebounceMillis
            preferences[Keys.YoutubeShortsEnabled] = settings.youtubeShortsEnabled
            preferences[Keys.BlockingEnabled] = settings.blockingEnabled
            preferences[Keys.ShortsBlockedToday] = settings.shortsBlockedToday
            preferences[Keys.CooldownsTriggeredToday] = settings.cooldownsTriggeredToday
            preferences[Keys.DebugLogging] = settings.debugLogging
            preferences[Keys.CurrentSessionCount] = settings.currentSessionCount
        }
    }

    private object Keys {
        val SessionLimit = intPreferencesKey("session_limit")
        val CooldownDurationMillis = longPreferencesKey("cooldown_duration_millis")
        val WarningDurationMillis = longPreferencesKey("warning_duration_millis")
        val EventDebounceMillis = longPreferencesKey("event_debounce_millis")
        val YoutubeShortsEnabled = booleanPreferencesKey("youtube_shorts_enabled")
        val BlockingEnabled = booleanPreferencesKey("blocking_enabled")
        val ShortsBlockedToday = intPreferencesKey("shorts_blocked_today")
        val CooldownsTriggeredToday = intPreferencesKey("cooldowns_triggered_today")
        val DebugLogging = booleanPreferencesKey("debug_logging")
        val CurrentSessionCount = intPreferencesKey("current_session_count")
    }
}
