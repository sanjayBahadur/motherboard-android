package com.motherboard.focus.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow

enum class ShortsDetectionState {
    /** Package is not com.google.android.youtube */
    NotYouTube,
    /** YouTube is foreground but no Shorts markers found after retries */
    YouTubeNotShorts,
    /** Shorts-specific view IDs confirmed in the active window */
    YouTubeShorts,
}

class YouTubeShortsDetector {

    companion object {
        private const val TAG = "MotherboardA11y"

        /** UI observes this to know what screen the user is on */
        val detectionState = MutableStateFlow(ShortsDetectionState.NotYouTube)

        /** Delay between retry attempts (ms) */
        const val RETRY_DELAY_MS = 300L

        /** Maximum retry attempts when the accessibility tree isn't ready */
        const val MAX_RETRIES = 3

        /** Minimum interval between node tree inspections (ms) */
        private const val DETECTION_DEBOUNCE_MS = 750L

        /**
         * View IDs that indicate the YouTube Shorts player is active.
         * Ordered by reliability: reel_player_page_container (Scrolless 2026, newest),
         * reel_recycler (5+ projects), reel_progress_bar (unique to active Shorts player).
         */
        private val SHORTS_VIEW_IDS = listOf(
            "com.google.android.youtube:id/reel_player_page_container",
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_progress_bar",
        )
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Incremented on each new detection attempt — stale retries ignore mismatched generation */
    private var retryGeneration = 0

    /** Timestamp of last node tree inspection (for debounce) */
    private var lastDetectionTime = 0L

    /**
     * Called from [MotherboardAccessibilityService.onAccessibilityEvent].
     * Handles the full detection lifecycle: package check, debounce, node inspection with retry.
     *
     * @param event   The accessibility event. Used to extract package name.
     * @param service The running service. Used to call [AccessibilityService.rootInActiveWindow].
     * @param debug   Whether to emit debug log statements.
     */
    fun onAccessibilityEvent(event: AccessibilityEvent, service: AccessibilityService, debug: Boolean) {
        val pkg = event.packageName?.toString() ?: ""

        if (pkg != "com.google.android.youtube") {
            reset(debug)
            return
        }

        // Debounce: skip node tree inspection if the last one was too recent
        val now = SystemClock.uptimeMillis()
        if (now - lastDetectionTime < DETECTION_DEBOUNCE_MS) {
            if (debug) Log.d(TAG, "detection: skipped (debounce)")
            return
        }
        lastDetectionTime = now

        // Cancel any pending retry and start a new detection cycle
        retryGeneration++
        val generation = retryGeneration

        if (debug) {
            val typeName = event.eventType.eventTypeToString()
            Log.d(TAG, "detection: checking cls=${event.className} type=$typeName gen=$generation")
        }

        performDetection(service, generation, retriesLeft = MAX_RETRIES, debug)
    }

    /**
     * Recursive detection attempt via [Handler.postDelayed].
     * Each call inspects [AccessibilityService.rootInActiveWindow] for Shorts view IDs.
     * If the tree isn't ready (root is null) OR no Shorts IDs are found yet,
     * schedules a retry after [RETRY_DELAY_MS].
     * If the generation has been superseded (new event arrived), the retry is silently dropped.
     */
    private fun performDetection(
        service: AccessibilityService,
        generation: Int,
        retriesLeft: Int,
        debug: Boolean,
    ) {
        if (generation != retryGeneration) {
            if (debug) Log.d(TAG, "detection: retry dropped (generation $generation != $retryGeneration)")
            return
        }

        val root = service.rootInActiveWindow
        if (root == null) {
            if (debug) Log.d(TAG, "detection: root null, retries=$retriesLeft")
            scheduleRetryOrFail(service, generation, retriesLeft, debug)
            return
        }

        try {
            val found = SHORTS_VIEW_IDS.any { viewId ->
                val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
                val hasNodes = nodes.isNotEmpty()
                nodes.forEach { it.recycle() }
                if (hasNodes && debug) Log.d(TAG, "detection: matched $viewId → YouTubeShorts")
                hasNodes
            }
            if (found) {
                detectionState.value = ShortsDetectionState.YouTubeShorts
            } else {
                // Root exists but no Shorts IDs — nodes may not be rendered yet. Retry.
                if (debug) Log.d(TAG, "detection: root OK, no Shorts IDs, retries=$retriesLeft")
                scheduleRetryOrFail(service, generation, retriesLeft, debug)
            }
        } finally {
            root.recycle()
        }
    }

    private fun scheduleRetryOrFail(
        service: AccessibilityService,
        generation: Int,
        retriesLeft: Int,
        debug: Boolean,
    ) {
        if (retriesLeft > 0) {
            handler.postDelayed(
                { performDetection(service, generation, retriesLeft - 1, debug) },
                RETRY_DELAY_MS,
            )
        } else {
            detectionState.value = ShortsDetectionState.YouTubeNotShorts
            if (debug) Log.d(TAG, "detection: retries exhausted → YouTubeNotShorts")
        }
    }

    /**
     * Cancel all pending retries and set state to [ShortsDetectionState.NotYouTube].
     * Called when the foreground app is not YouTube or when the service is destroyed.
     */
    fun reset(debug: Boolean = false) {
        retryGeneration++ // invalidate all pending retries
        handler.removeCallbacksAndMessages(null) // release queued callbacks immediately
        if (detectionState.value != ShortsDetectionState.NotYouTube) {
            detectionState.value = ShortsDetectionState.NotYouTube
            if (debug) Log.d(TAG, "detection: state reset → NotYouTube")
        }
    }

    private fun Int.eventTypeToString(): String = when (this) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
        else -> "TYPE_$this"
    }
}
