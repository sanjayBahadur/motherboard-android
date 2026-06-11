package com.motherboard.focus.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

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
    }

    private val detector = YouTubeShortsDetector()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning.value = true
        detector.reset(debugLogging)
        if (debugLogging) {
            Log.d(TAG, "onServiceConnected: service bound")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Detection runs UNCONDITIONALLY — not gated behind debugLogging
        detector.onAccessibilityEvent(event, this, debugLogging)

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
