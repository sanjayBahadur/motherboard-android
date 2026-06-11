package com.motherboard.focus.service

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.motherboard.focus.storage.InterventionSettings
import com.motherboard.focus.storage.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MotherboardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MotherboardA11y"

        /** UI observes this to know whether the service is connected/running */
        val isRunning = kotlinx.coroutines.flow.MutableStateFlow(false)

        /** Direct access for ViewModel to read state or call methods. Set by ViewModel from DataStore. */
        @Volatile
        var debugLogging: Boolean = false

        /** Service instance for direct method calls from the app process */
        @Volatile
        var instance: MotherboardAccessibilityService? = null
            private set

        // ── Phase 3: counting gates, synced from DataStore by ViewModel ──

        /** Whether YouTube Shorts blocking is enabled. Synced from DataStore by ViewModel. */
        @Volatile
        var blockingEnabled: Boolean = true

        /** Debounce window for scroll counting (ms). Synced from DataStore by ViewModel. */
        @Volatile
        var eventDebounceMillis: Long = 1000L

        /** Timestamp of the last counted scroll (for debounce). Runtime-only, not persisted. */
        @Volatile
        var lastCountedAtMs: Long = 0L
    }

    private val detector = YouTubeShortsDetector()
    private lateinit var warningOverlay: WarningOverlay
    private var settingsStore: SettingsStore? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning.value = true
        warningOverlay = WarningOverlay(this)
        settingsStore = SettingsStore(applicationContext)
        detector.reset(debugLogging)
        if (debugLogging) {
            Log.d(TAG, "onServiceConnected: service bound")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Detection runs UNCONDITIONALLY — not gated behind debugLogging
        detector.onAccessibilityEvent(event, this, debugLogging)

        // ── Phase 3: Scroll counting ──
        tryCountScroll(event)

        // Logging is conditional
        if (debugLogging) {
            val eventTypeName = event.eventType.eventTypeToString()
            val state = YouTubeShortsDetector.detectionState.value
            Log.d(
                TAG,
                "event: type=$eventTypeName pkg=${event.packageName} cls=${event.className} time=${event.eventTime} state=$state"
            )
        }
    }

    /**
     * Check all counting gates and increment the session counter if conditions are met.
     * Counting rules (order matters — fail fast):
     * 1. Service must be running
     * 2. Blocking must be enabled (user toggle)
     * 3. Must be a scroll event
     * 4. Must be on the YouTube Shorts player screen
     * 5. Debounce window must have passed since last counted scroll
     */
    private fun tryCountScroll(event: AccessibilityEvent) {
        // Gate 1: Service must be running
        if (!isRunning.value) return

        // Gate 2: Blocking must be enabled
        if (!blockingEnabled) return

        // Gate 3: Must be a scroll event
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        // Gate 4: Must be on the YouTube Shorts player
        if (YouTubeShortsDetector.detectionState.value != ShortsDetectionState.YouTubeShorts) return

        // Gate 5: Debounce — don't count rapid repeated scrolls
        val now = SystemClock.uptimeMillis()
        if (now - lastCountedAtMs < eventDebounceMillis) return
        lastCountedAtMs = now

        // All gates passed — persist count and show overlay
        val store = settingsStore ?: return
        serviceScope.launch {
            try {
                val current = store.settings.first()
                val newCount = current.currentSessionCount + 1
                store.save(current.copy(currentSessionCount = newCount))
                warningOverlay.show(newCount, current.sessionLimit)

                if (debugLogging) {
                    Log.d(TAG, "count: scroll counted → $newCount / ${current.sessionLimit}")
                }
            } catch (e: Exception) {
                if (debugLogging) {
                    Log.e(TAG, "count: failed to persist count", e)
                }
            }
        }
    }

    override fun onInterrupt() {
        if (debugLogging) {
            Log.d(TAG, "onInterrupt: system requested interrupt")
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (debugLogging) {
            Log.d(TAG, "onUnbind: service shutting down")
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        isRunning.value = false
        detector.reset(debugLogging)
        warningOverlay.destroy()
        if (debugLogging) {
            Log.d(TAG, "onDestroy: service destroyed")
        }
        super.onDestroy()
    }

    private fun Int.eventTypeToString(): String = when (this) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "VIEW_LONG_CLICKED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "VIEW_SELECTED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "VIEW_TEXT_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOWS_CHANGED"
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> "TOUCH_INTERACTION_START"
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> "TOUCH_INTERACTION_END"
        else -> "TYPE_$this"
    }
}
