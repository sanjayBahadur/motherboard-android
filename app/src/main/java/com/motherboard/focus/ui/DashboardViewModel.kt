package com.motherboard.focus.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motherboard.focus.service.MotherboardAccessibilityService
import com.motherboard.focus.service.ShortsDetectionState
import com.motherboard.focus.service.YouTubeShortsDetector
import com.motherboard.focus.storage.InterventionSettings
import com.motherboard.focus.storage.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** ContentObserver-backed real-time accessibility service enabled state */
    private val _isAccessibilityServiceEnabled = MutableStateFlow(
        isAccessibilityServiceEnabled()
    )
    val isAccessibilityServiceEnabled: StateFlow<Boolean> = _isAccessibilityServiceEnabled

    /** Directly exposes the detector's companion StateFlow — all three ShortsDetectionState values */
    val detectionState: StateFlow<ShortsDetectionState> = YouTubeShortsDetector.detectionState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ShortsDetectionState.NotYouTube,
        )

    private val contentObserver = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            _isAccessibilityServiceEnabled.value = isAccessibilityServiceEnabled()
        }
    }

    init {
        val app = getApplication<Application>()
        app.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            contentObserver,
        )
        // Sync debugLogging flag from DataStore to service companion
        viewModelScope.launch {
            store.settings.collect { s ->
                MotherboardAccessibilityService.debugLogging = s.debugLogging
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().contentResolver.unregisterContentObserver(contentObserver)
    }

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

    fun setDebugLogging(enabled: Boolean) {
        update { it.copy(debugLogging = enabled) }
    }

    private fun update(transform: (InterventionSettings) -> InterventionSettings) {
        viewModelScope.launch {
            store.save(transform(settings.value))
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val manager = getApplication<Application>()
            .getSystemService(Application.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val enabledServices = manager.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabledServices.any { info ->
            info.resolveInfo.serviceInfo.packageName == getApplication<Application>().packageName &&
                    info.resolveInfo.serviceInfo.name == MotherboardAccessibilityService::class.qualifiedName
        }
    }
}
