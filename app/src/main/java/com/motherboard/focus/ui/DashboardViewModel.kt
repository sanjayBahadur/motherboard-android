package com.motherboard.focus.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motherboard.focus.storage.InterventionSettings
import com.motherboard.focus.storage.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SettingsStore(application)

    val settings: StateFlow<InterventionSettings> = store.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = InterventionSettings(),
        )

    fun setSessionLimit(limit: Int) {
        update { it.copy(sessionLimit = limit.coerceIn(3, 50)) }
    }

    fun setCooldownMinutes(minutes: Int) {
        val millis = minutes.coerceIn(1, 30) * 60 * 1000L
        update { it.copy(cooldownDurationMillis = millis) }
    }

    fun setBlockingEnabled(enabled: Boolean) {
        update { it.copy(blockingEnabled = enabled) }
    }

    private fun update(transform: (InterventionSettings) -> InterventionSettings) {
        viewModelScope.launch {
            store.save(transform(settings.value))
        }
    }
}
