# Motherboard Phase 2: YouTube Shorts Detection

## Plan Metadata

| Field | Value |
|---|---|
| **Plan ID** | `phase-2` |
| **Created** | 2026-06-11 |
| **Status** | Ready for execution |
| **App** | Motherboard (YouTube Shorts blocker) |
| **Prerequisite** | Phase 1 COMPLETE (AccessibilityService skeleton, all tests pass, build passes) |
| **Phase scope** | YouTubeShortsDetector class, config XML changes (canRetrieveWindowContent=true, flagReportViewIds), service event handling update, dashboard detection indicator, debug logging for detection decisions |
| **Phase explicitly OUT** | Scroll counting (Phase 3), warning overlays (Phase 3), blocking (Phase 4), TYPE_VIEW_SCROLLED events, DataStore schema changes, INTERNET, AI, analytics |
| **Package** | `com.motherboard.focus` (unchanged) |
| **New file** | `com.motherboard.focus.service.YouTubeShortsDetector` |
| **Tech stack** | Kotlin, Android AccessibilityService API, AccessibilityNodeInfo, Handler/Looper, MutableStateFlow |

## Architecture Decisions (Frozen)

| Decision | Choice | Rationale |
|---|---|---|
| Detector location | `com.motherboard.focus.service.YouTubeShortsDetector` | Same package as service — no new sub-package warranted for single class |
| Detection state enum | `ShortsDetectionState { NotYouTube, YouTubeNotShorts, YouTubeShorts }` | Three-state model from PHASED_BUILD_AND_TEST_PLAN. `NotYouTube` = package not YouTube; `YouTubeNotShorts` = YouTube foreground but no Shorts markers found (after retries); `YouTubeShorts` = Shorts markers confirmed |
| State exposure | `YouTubeShortsDetector.Companion.detectionState: MutableStateFlow<ShortsDetectionState>` | Same companion MutableStateFlow pattern as `MotherboardAccessibilityService.isRunning`. ViewModel observes directly — no service companion changes needed |
| Detection method | `rootInActiveWindow.findAccessibilityNodeInfosByViewId(fullyQualifiedId)` | Simpler than BFS traversal, production-proven across 5+ open-source apps (digipaws, AntiScroll, Shorts-Blocker). BFS can be added in Phase 5 if needed |
| Retry pattern | `Handler.postDelayed`, 300ms × 3 attempts, generation token | YouTube renders accessibility tree asynchronously — single query fails ~20% of the time. Generation token cancels stale retries on package change or service destroy. Non-blocking via Handler (posts to main Looper message queue) |
| Detection trigger | `TYPE_WINDOW_STATE_CHANGED` + `TYPE_WINDOW_CONTENT_CHANGED` | Already declared in config XML. `TYPE_WINDOW_STATE_CHANGED` fires on screen transitions; `TYPE_WINDOW_CONTENT_CHANGED` catches content rendering within Shorts |
| Detection vs logging | Detection runs UNCONDITIONALLY; only `Log.d()` calls are gated by `debugLogging` | Oracle feedback: Phase 1 service returns early when `!debugLogging` — Phase 2 must NOT gate detection behind debug toggle. The debug flag controls logging verbosity only |
| Debounce for detection | Fixed 750ms detector-internal cooldown (not from DataStore) | Separate from `InterventionSettings.eventDebounceMillis` (1000ms, used for scroll counting in Phase 3). Detection runs on every event but inspects the node tree at most once every 750ms to avoid redundant work |
| Node recycling | All `AccessibilityNodeInfo` objects recycled in `finally` block | Prevents memory leaks — Android's node pool is limited. Root node + all nodes from `findAccessibilityNodeInfosByViewId` must be recycled |
| View IDs | Fully qualified: `com.google.android.youtube:id/reel_player_page_container`, `reel_recycler`, `reel_progress_bar` | Oracle feedback: full qualification avoids ambiguity. Ordered by reliability: `reel_player_page_container` (Scrolless 2026, newest), `reel_recycler` (5+ projects), `reel_progress_bar` (unique to active Shorts player) |
| Config XML flags | `flagReportViewIds` (replaces `flagDefault`) | Required for `viewIdResourceName` to be populated on AccessibilityNodeInfo nodes. Without this flag, `findAccessibilityNodeInfosByViewId` returns empty results |
| canRetrieveWindowContent | `true` (was `false` in Phase 1) | Required for `rootInActiveWindow` to return non-null. Phase 1 explicitly set it to `false` since no window content was needed |
| Package filter removal | REMOVE `android:packageNames="com.google.android.youtube"` from config XML | Oracle finding: with package filter, the service NEVER receives non-YouTube events, so `NotYouTube` state is unreachable. Remove package filter — filter in code instead (already done in detector's `pkg != "com.google.android.youtube"` check). Acceptable trade-off: receive events from all apps but early-return for non-YouTube with negligible battery impact |
| Dashboard indicator | Inline subtitle in status card showing full detection state | User chose inline placement. Only visible when accessibility service is enabled. Shows all three states: "Watching YouTube Shorts: Yes" (YouTubeShorts), "Watching YouTube (not Shorts)" (YouTubeNotShorts), "Not watching Shorts" (NotYouTube). Uses `bodySmall` typography, `tertiary` color for Shorts state, `onSurfaceVariant` otherwise |
| No DataStore changes | Detection state is runtime-only, not persisted | Restarting the app resets detection to `NotYouTube`. This is correct — detection state reflects current foreground app, not historical state |
| No new dependencies | `Handler`, `Looper`, `SystemClock` are all Android framework APIs | Zero additions to `libs.versions.toml` or `build.gradle.kts` |

## State Transition Contract (Frozen)

```
EVENT                        CURRENT STATE          →  NEW STATE
─────────────────────────────────────────────────────────────────
Any event, pkg ≠ YT          Any                    →  NotYouTube (immediate, cancel retries)
YT event, root == null       Any (during retry)     →  Retry (300ms × up to 3)
YT event, root == null       Any (retries exhausted) →  YouTubeNotShorts
YT event, root OK, ID ✓      Any                    →  YouTubeShorts (immediate)
YT event, root OK, ID ✗      Any (during retry)     →  Retry (300ms × up to 3) — Shorts nodes may not be rendered yet
YT event, root OK, ID ✗      Any (retries exhausted) →  YouTubeNotShorts
Service connected            Any                    →  NotYouTube (reset, cancel all retries)
Service destroyed            Any                    →  NotYouTube (reset, cancel all retries)
Debounce < 750ms             Any                    →  No change (skip detection)
```

**Retry rationale**: YouTube renders the accessibility tree asynchronously. When switching to Shorts, the root node appears first, THEN the Shorts-specific child nodes (reel_recycler, etc.) populate. A single check for matching IDs on the first root that appears would yield false negatives ~20% of the time. Retrying 3× at 300ms intervals (900ms total window) covers the async render delay while keeping latency acceptable. Similarly, when switching from Shorts to a non-Shorts YouTube screen, Shorts nodes linger briefly — retries prevent a transient false positive.

**Generation token**: Each new event increments `retryGeneration`. Stale callbacks check `generation != retryGeneration` and silently return. Handler callbacks are also cleared via `handler.removeCallbacksAndMessages(null)` in `reset()` to release references immediately.

## Dashboard Contract Delta

### Status card — new detection subtitle added

```
┌──────────────────────────────────────────┐
│  YouTube Shorts Blocking           [ON]  │
│  Active                                   │
│  Watching YouTube Shorts: Yes             │  ← NEW (only when isServiceEnabled)
└──────────────────────────────────────────┘
```

When service is not enabled, the detection line is hidden.

### State → subtitle mapping:

| Detection State | Subtitle Text | When Visible |
|---|---|---|
| `NotYouTube` | "Not watching Shorts" | Service enabled only |
| `YouTubeNotShorts` | "Watching YouTube (not Shorts)" | Service enabled only |
| `YouTubeShorts` | "Watching YouTube Shorts: Yes" | Service enabled only |
| Service disabled | (hidden) | Never |

## Guardrails

| Rule | Reason |
|---|---|
| Detection MUST NOT be gated behind `debugLogging` | Oracle finding: Phase 1 service returns early when `!debugLogging`. Phase 2 detection is unconditional — only `Log.d()` is conditional |
| All `AccessibilityNodeInfo` objects MUST be recycled in `finally` | Prevents memory leaks. Android node pool is limited. Root + all view-ID search results |
| Handler retries MUST use generation token AND `handler.removeCallbacksAndMessages(null)` in `reset()` | Generation token prevents stale callbacks from updating state. `removeCallbacksAndMessages(null)` immediately releases queued callbacks to avoid reference leaks |
| `android:packageNames` MUST be REMOVED from config XML | Oracle finding: with package filter, service NEVER receives non-YouTube events, making `NotYouTube` state unreachable. Code-level filtering (`pkg != "com.google.android.youtube"`) is the sole gate |
| Never block main thread — use `Handler.postDelayed`, not `Thread.sleep` | Metis finding: synchronous retry would freeze UI/service. Handler posts to message queue non-blockingly |
| `rootInActiveWindow` null MUST be handled gracefully | Android can return null — do not crash. Treat as "tree not ready" and retry |
| `performDetection` MUST retry on BOTH null root AND non-null root without Shorts IDs | Oracle finding: Shorts nodes render asynchronously AFTER the root appears. Immediate `YouTubeNotShorts` on non-null root without IDs would yield false negatives |
| Dashboard MUST show all three detection states | Oracle finding: boolean `watchingShorts` collapses `NotYouTube` and `YouTubeNotShorts` into one label. Use `ShortsDetectionState` directly with exhaustive `when` expression |
| ViewModel MUST import `ShortsDetectionState` and `YouTubeShortsDetector` | Missing imports cause compile failure. Task 4 explicitly lists both |
| Never log `event.text` or `event.contentDescription` | README constraint: "Do not store raw accessibility text" |
| Never increment counters or modify `InterventionSettings` | That's Phase 3 territory |
| Never add overlay views | That's Phase 3-4 territory |
| Never request `INTERNET` | Existing PhaseZeroContractTest enforces this |
| `detectionState` updates MUST be on main thread | `MutableStateFlow` is thread-safe, but Compose collects on main thread. Handler runs on main Looper — updates happen on main thread by design |
| Event debounce skips detection, not events | Debounce prevents redundant node tree inspection. Events are still received and logged (if debugLogging is on) |
| Detection state resets to `NotYouTube` on service connect/destroy | Clean slate — prevents stale `YouTubeShorts` from persisting across service restarts |

## Data Model Changes

**None.** `InterventionSettings` stays at 9 fields. `SettingsStore.Keys` stays at 9 keys. Detection state is runtime-only — persisted nowhere.

---

## Tasks

---

### Wave 1: Detector Class & Config (2 PARALLEL + 1 sequential)

Tasks 1 and 2 are independent and can run in parallel. Task 3 depends on Task 1 (detector class must exist for service to import it) and Task 2 (config XML must have `canRetrieveWindowContent="true"` for `rootInActiveWindow` to work).

---

#### Task 1: Create `YouTubeShortsDetector.kt`

**File**: `app/src/main/java/com/motherboard/focus/service/YouTubeShortsDetector.kt` (NEW)

**What**: New class that encapsulates all Shorts detection logic. Companion holds shared `MutableStateFlow<ShortsDetectionState>`. Instance methods handle event processing, node inspection with retry, debounce, and node recycling.

**Full content**:

```kotlin
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
```

**Key design decisions**:
- `detectionState`: Companion `MutableStateFlow<ShortsDetectionState>` — same pattern as `MotherboardAccessibilityService.isRunning`. ViewModel observes directly.
- `retryGeneration`: Monotonically increasing counter. Each call to `onAccessibilityEvent` increments it. Stale `Handler` callbacks check `generation != retryGeneration` and silently return if superseded. Additionally, `handler.removeCallbacksAndMessages(null)` in `reset()` immediately clears all queued callbacks.
- `performDetection()`: Takes `generation` parameter. All retries carry the same generation. If a new event arrives during retry, the old retries are invalidated.
- `scheduleRetryOrFail()`: Extracted helper — retries on BOTH null root AND non-null root without Shorts IDs. Shorts-specific nodes render asynchronously after the root appears. Only after `MAX_RETRIES` attempts are exhausted does the state become `YouTubeNotShorts`.
- **Node recycling**: `root.recycle()` in `finally` block. Each node from `findAccessibilityNodeInfosByViewId` recycled immediately after checking emptiness.
- **Debounce**: 750ms cooldown between tree inspections. Events still received — just skip redundant work.
- **Debug logging**: `debug` parameter passed from service. Only `Log.d()` calls are gated — detection state transitions always happen.
- **Package check**: `pkg != "com.google.android.youtube"` → immediate `reset()`. This handles app switches cleanly. Note: XML `packageNames` filter is REMOVED (Task 2), so the service receives events from ALL apps — the code-level filter here is the sole gate.
- `eventTypeToString()`: Private helper for debug logging only — minimal set of event types relevant to detection.

**QA**: Verify enum has exactly 3 values (`NotYouTube`, `YouTubeNotShorts`, `YouTubeShorts`). Verify `detectionState` is `MutableStateFlow` on companion. Verify `SHORTS_VIEW_IDS` uses fully qualified IDs. Verify `recycle()` appears in both `finally` and the `forEach` loop. Verify `retryGeneration` is incremented in `onAccessibilityEvent` and checked in `performDetection`. Verify `handler.removeCallbacksAndMessages(null)` in `reset()`. Verify `scheduleRetryOrFail` is called for BOTH null root AND no-match cases. Verify `DETECTION_DEBOUNCE_MS = 750L`. Verify `RETRY_DELAY_MS = 300L`. Verify `MAX_RETRIES = 3`.

---

#### Task 2: Update `accessibility_service_config.xml`

**File**: `app/src/main/res/xml/accessibility_service_config.xml`

**What**: Three changes: (1) Flip `canRetrieveWindowContent` from `false` to `true` — required for `rootInActiveWindow` to return non-null. (2) Change `flagDefault` to `flagReportViewIds` — required for `findAccessibilityNodeInfosByViewId` to populate view IDs on nodes. (3) **REMOVE `android:packageNames`** — with package filter, the service NEVER receives non-YouTube events, making the `NotYouTube` state unreachable. Filtering is done in code (detector's `pkg != "com.google.android.youtube"` check).

**Before**:
```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/accessibility_service_description"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault"
    android:canRetrieveWindowContent="false"
    android:notificationTimeout="300"
    android:packageNames="com.google.android.youtube"
    android:settingsActivity="com.motherboard.focus.MainActivity" />
```

**After**:
```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/accessibility_service_description"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="300"
    android:settingsActivity="com.motherboard.focus.MainActivity" />
```

**Changes**:
- Line 5: `android:accessibilityFlags="flagDefault"` → `"flagReportViewIds"`
- Line 6: `android:canRetrieveWindowContent="false"` → `"true"`
- Line 8: `android:packageNames="com.google.android.youtube"` → **REMOVED** entirely

**QA**: Verify `canRetrieveWindowContent="true"`. Verify `flagReportViewIds` present. Verify `typeViewScrolled` is NOT present (that's Phase 3). Verify `notificationTimeout="300"` unchanged. Verify `android:packageNames` is ABSENT from the file.

---

#### Task 3: Update `MotherboardAccessibilityService.kt` — wire detector

**File**: `app/src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt`

**What**: Create a `YouTubeShortsDetector` instance. Replace `onAccessibilityEvent` to call detector unconditionally (only logging is gated by `debugLogging`). Reset detector on service connect and destroy.

**Full replacement content**:

```kotlin
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
```

**Key changes from Phase 1**:
- **Line 30**: `private val detector = YouTubeShortsDetector()` — new instance field
- **Line 35**: `detector.reset(debugLogging)` added to `onServiceConnected()` — ensures clean state on bind
- **Lines 42-43**: Early return from `if (!debugLogging)` **REMOVED**. Detection runs unconditionally via `detector.onAccessibilityEvent(event, this, debugLogging)`.
- **Line 43**: Detector is called with `debugLogging` as the third parameter — detector gates its own `Log.d()` calls
- **Lines 46-52**: Logging block now includes `state=` in log output
- **Line 79**: `detector.reset(debugLogging)` added to `onDestroy()` — cancels pending retries, sets `NotYouTube`

**QA**: Verify `detector.onAccessibilityEvent()` is called BEFORE the `if (debugLogging)` check. Verify NO `if (!debugLogging) return` exists before the detector call. Verify `detector.reset()` is called in `onServiceConnected()` and `onDestroy()`. Verify log output includes `state=` field. Verify `YouTubeShortsDetector.detectionState.value` is read for logging. Verify all Phase 1 lifecycle hooks (`onInterrupt`, `onUnbind`) unchanged.

---

### Wave 2: ViewModel & UI Wiring (SEQUENTIAL within wave)

Task 4 exposes detection state from the ViewModel. Task 5 adds the dashboard indicator to HomeScreen. Task 6 wires them together in App.kt. Run sequentially: ViewModel first (exposes state), then HomeScreen (consumes it), then App (passes it).

---

#### Task 4: Update `DashboardViewModel.kt` — expose detection state

**File**: `app/src/main/java/com/motherboard/focus/ui/DashboardViewModel.kt`

**What**: Add a `detectionState: StateFlow<ShortsDetectionState>` that directly exposes the detector's companion StateFlow. This gives the dashboard access to all three states (`NotYouTube`, `YouTubeNotShorts`, `YouTubeShorts`). No DataStore changes needed.

**Additional imports needed** (add at top of file with other imports):
```kotlin
import com.motherboard.focus.service.ShortsDetectionState
import com.motherboard.focus.service.YouTubeShortsDetector
```

**Change**: Insert new `detectionState` property AFTER the `isAccessibilityServiceEnabled` property (after current line 32) and BEFORE the `contentObserver` field (before current line 34):

```kotlin
    /** Directly exposes the detector's companion StateFlow — all three ShortsDetectionState values */
    val detectionState: StateFlow<ShortsDetectionState> = YouTubeShortsDetector.detectionState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ShortsDetectionState.NotYouTube,
        )
```

**QA**: Verify `detectionState` is `StateFlow<ShortsDetectionState>`. Verify initial value is `NotYouTube`. Verify imports for `ShortsDetectionState` and `YouTubeShortsDetector`. Verify no DataStore read/write added.

---

#### Task 5: Update `HomeScreen.kt` — add detection indicator in status card

**File**: `app/src/main/java/com/motherboard/focus/ui/screens/HomeScreen.kt`

**What**: Add `detectionState: ShortsDetectionState` parameter. Add detection subtitle in the blocking toggle DashboardCard (after the existing "Active"/"Paused" subtitle). Show all three states mapped to distinct text. Only show detection info when `isServiceEnabled` is true.

**Additional import needed** (add at top with other imports):
```kotlin
import com.motherboard.focus.service.ShortsDetectionState
```

**Change 1** — Update function signature (line 22):
```kotlin
@Composable
fun HomeScreen(
    settings: InterventionSettings,
    isServiceEnabled: Boolean,
    detectionState: ShortsDetectionState,                // NEW parameter
    onToggleBlocking: (Boolean) -> Unit,
    onSessionLimitChange: (Int) -> Unit,
    onCooldownMinutesChange: (Int) -> Unit,
    onToggleDebugLogging: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
)
```

**Change 2** — Add detection subtitle in the status card. Insert AFTER the existing "Active"/"Paused" subtitle (after current line 62, inside the `Column`):

```kotlin
                    if (isServiceEnabled) {
                        Text(
                            when (detectionState) {
                                ShortsDetectionState.YouTubeShorts -> "Watching YouTube Shorts: Yes"
                                ShortsDetectionState.YouTubeNotShorts -> "Watching YouTube (not Shorts)"
                                ShortsDetectionState.NotYouTube -> "Not watching Shorts"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (detectionState == ShortsDetectionState.YouTubeShorts)
                                MaterialTheme.colorScheme.tertiary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
```

**The status card Column after changes (lines 55-67)**:
```kotlin
                Column {
                    Text("YouTube Shorts Blocking", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (settings.blockingEnabled) "Active" else "Paused",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isServiceEnabled) {
                        Text(
                            when (detectionState) {
                                ShortsDetectionState.YouTubeShorts -> "Watching YouTube Shorts: Yes"
                                ShortsDetectionState.YouTubeNotShorts -> "Watching YouTube (not Shorts)"
                                ShortsDetectionState.NotYouTube -> "Not watching Shorts"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (detectionState == ShortsDetectionState.YouTubeShorts)
                                MaterialTheme.colorScheme.tertiary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
```

**Design note**: Three-line subtitle: "Active"/"Paused" (always visible), detection line (only when service enabled). All three states mapped: `YouTubeShorts` → "Watching YouTube Shorts: Yes" (tertiary/amber color), `YouTubeNotShorts` → "Watching YouTube (not Shorts)" (muted), `NotYouTube` → "Not watching Shorts" (muted). The `when` expression is exhaustive — Compiler ensures all enum values are handled.

**QA**: Verify `detectionState` parameter uses `ShortsDetectionState` type (not Boolean). Verify all three enum values are handled in `when` expression. Verify detection subtitle only visible when `isServiceEnabled == true`. Verify import for `ShortsDetectionState`. Verify no crash when service disabled (detection line hidden).

---

#### Task 6: Update `App.kt` — collect and pass detection state

**File**: `app/src/main/java/com/motherboard/focus/ui/App.kt`

**What**: Collect `detectionState` from ViewModel and pass it to HomeScreen. Small wiring change.

**Full replacement**:
```kotlin
package com.motherboard.focus.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.motherboard.focus.ui.screens.HomeScreen

@Composable
fun MotherboardApp(viewModel: DashboardViewModel) {
    val settings by viewModel.settings.collectAsState()
    val isServiceEnabled by viewModel.isAccessibilityServiceEnabled.collectAsState()
    val detectionState by viewModel.detectionState.collectAsState()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        HomeScreen(
            settings = settings,
            isServiceEnabled = isServiceEnabled,
            detectionState = detectionState,
            onToggleBlocking = viewModel::setBlockingEnabled,
            onSessionLimitChange = viewModel::setSessionLimit,
            onCooldownMinutesChange = viewModel::setCooldownMinutes,
            onToggleDebugLogging = viewModel::setDebugLogging,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
```

**Change**: Added `val detectionState by viewModel.detectionState.collectAsState()` and `detectionState = detectionState` parameter. All existing wiring unchanged.

**QA**: Verify `detectionState` is collected via `collectAsState()`. Verify `detectionState` is passed to `HomeScreen` as the 3rd positional parameter (matching updated signature with `ShortsDetectionState` type). Verify build compiles — signature change is consistent across all files.

---

### Wave 3: Test & Verification Gates

Contract tests and build verification.

---

#### Task 7: Create `PhaseTwoContractTest.kt` — new contract tests

**File**: `app/src/test/java/com/motherboard/focus/PhaseTwoContractTest.kt` (NEW)

**What**: Contract tests following Phase 0/1 conventions. Verify detector class structure, enum values, config XML changes, service wiring, and that detection is not gated behind debugLogging.

**Full content**:
```kotlin
package com.motherboard.focus

import com.motherboard.focus.service.ShortsDetectionState
import com.motherboard.focus.service.YouTubeShortsDetector
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PhaseTwoContractTest {

    // ── Detector class structure ──

    @Test
    fun `YouTubeShortsDetector class exists`() {
        val detectorFile = File("src/main/java/com/motherboard/focus/service/YouTubeShortsDetector.kt")
        assertTrue("YouTubeShortsDetector.kt must exist", detectorFile.exists())
    }

    @Test
    fun `ShortsDetectionState enum has exactly 3 values`() {
        val values = ShortsDetectionState.entries
        assertEquals("must have exactly 3 values", 3, values.size)
        assertTrue("must have NotYouTube", ShortsDetectionState.valueOf("NotYouTube") != null)
        assertTrue("must have YouTubeNotShorts", ShortsDetectionState.valueOf("YouTubeNotShorts") != null)
        assertTrue("must have YouTubeShorts", ShortsDetectionState.valueOf("YouTubeShorts") != null)
    }

    @Test
    fun `YouTubeShortsDetector companion exposes detectionState MutableStateFlow`() {
        val detectorContent = File("src/main/java/com/motherboard/focus/service/YouTubeShortsDetector.kt").readText()
        assertTrue("detectionState must be MutableStateFlow", detectorContent.contains("MutableStateFlow"))
        assertTrue("detectionState must be on companion", detectorContent.contains("companion object"))
    }

    @Test
    fun `YouTubeShortsDetector has retry constants`() {
        val detectorContent = File("src/main/java/com/motherboard/focus/service/YouTubeShortsDetector.kt").readText()
        assertTrue("RETRY_DELAY_MS must be 300", detectorContent.contains("RETRY_DELAY_MS = 300"))
        assertTrue("MAX_RETRIES must be 3", detectorContent.contains("MAX_RETRIES = 3"))
    }

    @Test
    fun `YouTubeShortsDetector uses Handler for retry`() {
        val detectorContent = File("src/main/java/com/motherboard/focus/service/YouTubeShortsDetector.kt").readText()
        assertTrue("Handler must be imported or used", detectorContent.contains("Handler"))
        assertTrue("postDelayed must be used for retry", detectorContent.contains("postDelayed"))
    }

    @Test
    fun `YouTubeShortsDetector has generation token for cancellation`() {
        val detectorContent = File("src/main/java/com/motherboard/focus/service/YouTubeShortsDetector.kt").readText()
        assertTrue("retryGeneration must be present", detectorContent.contains("retryGeneration"))
    }

    @Test
    fun `YouTubeShortsDetector recycles AccessibilityNodeInfo`() {
        val detectorContent = File("src/main/java/com/motherboard/focus/service/YouTubeShortsDetector.kt").readText()
        assertTrue("recycle() must be called on nodes", detectorContent.contains("recycle()"))
        assertTrue("recycle must be in finally block or forEach", detectorContent.contains("finally") || detectorContent.contains("forEach"))
    }

    @Test
    fun `YouTubeShortsDetector has scheduleRetryOrFail helper`() {
        val detectorContent = File("src/main/java/com/motherboard/focus/service/YouTubeShortsDetector.kt").readText()
        assertTrue("scheduleRetryOrFail must be present for retry on both null root and no-match",
            detectorContent.contains("scheduleRetryOrFail"))
    }

    @Test
    fun `YouTubeShortsDetector reset clears handler callbacks`() {
        val detectorContent = File("src/main/java/com/motherboard/focus/service/YouTubeShortsDetector.kt").readText()
        assertTrue("removeCallbacksAndMessages must be in reset()",
            detectorContent.contains("removeCallbacksAndMessages"))
    }

    @Test
    fun `YouTubeShortsDetector uses fully qualified view IDs`() {
        val detectorContent = File("src/main/java/com/motherboard/focus/service/YouTubeShortsDetector.kt").readText()
        assertTrue("reel_player_page_container must be fully qualified",
            detectorContent.contains("com.google.android.youtube:id/reel_player_page_container"))
        assertTrue("reel_recycler must be fully qualified",
            detectorContent.contains("com.google.android.youtube:id/reel_recycler"))
        assertTrue("reel_progress_bar must be fully qualified",
            detectorContent.contains("com.google.android.youtube:id/reel_progress_bar"))
    }

    // ── Config XML changes ──

    @Test
    fun `accessibility service config has canRetrieveWindowContent true`() {
        val config = File("src/main/res/xml/accessibility_service_config.xml").readText()
        assertTrue("canRetrieveWindowContent must be true", config.contains("canRetrieveWindowContent=\"true\""))
    }

    @Test
    fun `accessibility service config has flagReportViewIds`() {
        val config = File("src/main/res/xml/accessibility_service_config.xml").readText()
        assertTrue("flagReportViewIds must be present", config.contains("flagReportViewIds"))
    }

    @Test
    fun `accessibility service config has NO packageNames`() {
        val config = File("src/main/res/xml/accessibility_service_config.xml").readText()
        assertFalse("packageNames must be removed for NotYouTube transitions to work",
            config.contains("packageNames"))
    }

    @Test
    fun `accessibility service config does NOT include typeViewScrolled`() {
        val config = File("src/main/res/xml/accessibility_service_config.xml").readText()
        assertFalse("typeViewScrolled must NOT be in Phase 2 config", config.contains("typeViewScrolled"))
    }

    // ── Service wiring ──

    @Test
    fun `MotherboardAccessibilityService detection is not gated behind debugLogging`() {
        val serviceContent = File("src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt").readText()

        // Find the onAccessibilityEvent method body
        val methodStart = serviceContent.indexOf("override fun onAccessibilityEvent")
        assertTrue("onAccessibilityEvent must exist", methodStart >= 0)

        // The detector call must appear BEFORE any "if (debugLogging)" / "if (!debugLogging)" gate
        val bodyAfterMethod = serviceContent.substring(methodStart)
        val detectorCallPos = bodyAfterMethod.indexOf("detector.onAccessibilityEvent")
        val debugGatePos = bodyAfterMethod.indexOf("if (debugLogging)")

        assertTrue("detector.onAccessibilityEvent must be called", detectorCallPos >= 0)
        assertTrue(
            "detector call must appear before debugLogging gate (or debugLogging gate must not exist before detector)",
            debugGatePos < 0 || detectorCallPos < debugGatePos
        )
    }

    @Test
    fun `MotherboardAccessibilityService resets detector on connect and destroy`() {
        val serviceContent = File("src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt").readText()
        assertTrue("detector.reset must be in onServiceConnected", serviceContent.contains("detector.reset"))
        // Count occurrences: should appear at least twice (onServiceConnected + onDestroy)
        val resetCount = serviceContent.split("detector.reset").size - 1
        assertTrue("detector.reset must be called at least twice (connect + destroy)", resetCount >= 2)
    }

    @Test
    fun `MotherboardAccessibilityService log output includes detection state`() {
        val serviceContent = File("src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt").readText()
        assertTrue("log must include state=", serviceContent.contains("state="))
    }

    // ── UI state rendering ──

    @Test
    fun `HomeScreen renders all three detection states`() {
        val homeScreenContent = File("src/main/java/com/motherboard/focus/ui/screens/HomeScreen.kt").readText()
        assertTrue("YouTubeShorts text must be present", homeScreenContent.contains("Watching YouTube Shorts: Yes"))
        assertTrue("YouTubeNotShorts text must be present", homeScreenContent.contains("Watching YouTube (not Shorts)"))
        assertTrue("NotYouTube text must be present", homeScreenContent.contains("Not watching Shorts"))
    }

    @Test
    fun `HomeScreen uses ShortsDetectionState type not Boolean for detection`() {
        val homeScreenContent = File("src/main/java/com/motherboard/focus/ui/screens/HomeScreen.kt").readText()
        // The function signature should include ShortsDetectionState
        assertTrue("HomeScreen must use ShortsDetectionState parameter", homeScreenContent.contains("ShortsDetectionState"))
    }

    @Test
    fun `ViewModel imports ShortsDetectionState`() {
        val viewModelContent = File("src/main/java/com/motherboard/focus/ui/DashboardViewModel.kt").readText()
        assertTrue("DashboardViewModel must import ShortsDetectionState",
            viewModelContent.contains("import com.motherboard.focus.service.ShortsDetectionState"))
        assertTrue("DashboardViewModel must reference detectionState",
            viewModelContent.contains("detectionState"))
    }

    // ── Backward compatibility ──

    @Test
    fun `InterventionSettings still has 9 fields after Phase 2`() {
        val fieldNames = com.motherboard.focus.storage.InterventionSettings::class.java.declaredFields.map { it.name }.toSet()
        assertEquals("Phase 2 adds no new fields to InterventionSettings", 9, fieldNames.size)
        assertTrue("debugLogging must still exist", "debugLogging" in fieldNames)
    }

    @Test
    fun `no INTERNET permission in manifest`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("INTERNET must not be declared", manifest.contains("android.permission.INTERNET"))
    }
}
```

**QA**: All tests pass with `./gradlew testDebugUnitTest`. Verify no Mockito/MockK needed (pure assertions + file reading only). Verify 22 tests total.

---

#### Task 8: Update `PhaseOneContractTest.kt` — fix stale assertions

**File**: `app/src/test/java/com/motherboard/focus/PhaseOneContractTest.kt`

**What**: Phase 2 changes two things that the Phase 1 contract tests assert: (1) `canRetrieveWindowContent` is now `"true"` (was `"false"`), and (2) `packageNames` is now ABSENT (was `"com.google.android.youtube"`). Update both assertions.

**Change 1** — In the `accessibility service config XML exists` test, replace the `canRetrieveWindowContent` assertion (line 59) and add a `packageNames` absence check:

**Before** (lines 52-60):
```kotlin
    @Test
    fun `accessibility service config XML exists`() {
        val config = File("src/main/res/xml/accessibility_service_config.xml")
        assertTrue("accessibility_service_config.xml must exist", config.exists())
        val content = config.readText()
        assertTrue("must filter for YouTube", content.contains("com.google.android.youtube"))
        assertTrue("must include typeWindowStateChanged", content.contains("typeWindowStateChanged"))
        assertTrue("must include typeWindowContentChanged", content.contains("typeWindowContentChanged"))
        assertFalse("must NOT include typeViewScrolled in Phase 1", content.contains("typeViewScrolled"))
        assertTrue("canRetrieveWindowContent must be false", content.contains("canRetrieveWindowContent=\"false\""))
    }
```

**After**:
```kotlin
    @Test
    fun `accessibility service config XML exists`() {
        val config = File("src/main/res/xml/accessibility_service_config.xml")
        assertTrue("accessibility_service_config.xml must exist", config.exists())
        val content = config.readText()
        assertTrue("must include typeWindowStateChanged", content.contains("typeWindowStateChanged"))
        assertTrue("must include typeWindowContentChanged", content.contains("typeWindowContentChanged"))
        assertFalse("must NOT include typeViewScrolled", content.contains("typeViewScrolled"))
        assertTrue("canRetrieveWindowContent must be true in Phase 2", content.contains("canRetrieveWindowContent=\"true\""))
        assertFalse("packageNames must be removed in Phase 2 for NotYouTube transitions",
            content.contains("packageNames"))
    }
```

**QA**: Verify `canRetrieveWindowContent` now expects `"true"`. Verify `packageNames` is now asserted as ABSENT. Verify `typeViewScrolled` check still asserts false. Note: the old `com.google.android.youtube` string check is removed — with `packageNames` gone, that literal no longer appears in the config XML (it's only in the detector code and strings.xml now).

---

#### Task 9: Run `./gradlew assembleDebug`

**Command**:
```bash
./gradlew assembleDebug
```

**Expected**: BUILD SUCCESSFUL. Zero compilation errors.

**If fails**: Check for signature mismatches between HomeScreen.kt and App.kt (7-parameter call). Check for missing imports in DashboardViewModel.kt (need `import com.motherboard.focus.service.ShortsDetectionState` and `import com.motherboard.focus.service.YouTubeShortsDetector`).

---

#### Task 10: Run `./gradlew testDebugUnitTest`

**Command**:
```bash
./gradlew testDebugUnitTest
```

**Expected**: All tests pass. PhaseZeroContractTest (8 tests) + PhaseOneContractTest (8 tests, 1 updated) + PhaseTwoContractTest (22 tests) = 38 tests all passing.

---

#### Task 11: Cruft grep — no detection gating

**Command**:
```bash
grep -n "if (!debugLogging) return" app/src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt
```

**Expected**: Zero matches (exit code 1). The early return before the detector call must be removed.

---

## Dependency Graph

```
Wave 1: Tasks 1-2 PARALLEL → Task 3 SEQUENTIAL
  Task 1 (YouTubeShortsDetector.kt) ──┐
                                       ├──→ Task 3 (MotherboardAccessibilityService.kt)
  Task 2 (accessibility_service_config.xml) ──┘
                                       │
                                       ▼
Wave 2: SEQUENTIAL within wave
  Task 4 (DashboardViewModel.kt) → Task 5 (HomeScreen.kt) → Task 6 (App.kt)
                                       │
                                       ▼
Wave 3: SEQUENTIAL
  Task 7 (PhaseTwoContractTest.kt) → Task 8 (update PhaseOneContractTest.kt) → Task 9 (build) → Task 10 (tests) → Task 11 (cruft grep)
```

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| YouTube changes view IDs after release | Medium | High (detection breaks) | Easy to update: single list of view IDs in companion. Acknowledged in MVP_SPEC — "not guaranteed compatibility" |
| `rootInActiveWindow` consistently null on some devices | Low | Medium | Retry pattern handles transient nulls. Persistent null = can't detect → state stays `YouTubeNotShorts` (safe false negative) |
| Handler retries cause ANR on slow devices | Low | Low | Handler posts to main Looper message queue — non-blocking. 3 retries × 300ms = 900ms max. Each callback does BFS search on root node tree — worst case 50-100ms for node inspection |
| Detection debounce too aggressive for fast navigation | Low | Low | 750ms debounce only skips tree inspection — events still received. If user switches from Shorts to home and back in <750ms, next event re-triggers detection. False negative window is at most 750ms |
| `findAccessibilityNodeInfosByViewId` returns stale nodes | Low | Medium | Nodes are short-lived (recycled by system after event). Each detection cycle queries fresh `rootInActiveWindow`. No node caching |
| PhaseOneContractTest assertion conflict (canRetrieveWindowContent) | LOW (fixed) | Medium | Task 8 explicitly updates the assertion from `"false"` to `"true"` |
| Compose recomposition on every detection state change | Low | Low | `StateFlow` + `collectAsState()` only recomposes the text composable. Detection state changes at most once per 750ms. Impact negligible |

---

## Final Verification Wave

| Gate | Command | Expected |
|---|---|---|
| **Build** | `./gradlew assembleDebug` | BUILD SUCCESSFUL |
| **Unit tests** | `./gradlew testDebugUnitTest` | All pass (PhaseZero + PhaseOne + PhaseTwo) |
| **No INTERNET** | `grep -r "INTERNET" app/src/main/AndroidManifest.xml` | Zero matches |
| **No raw text logging** | `grep -r "event.text" app/src/main/` and `grep -r "contentDescription" app/src/main/` | Zero matches |
| **Detection not gated** | `grep -n "if (!debugLogging) return" app/src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt` | Zero matches (exit code 1) |
| **canRetrieveWindowContent** | `grep 'canRetrieveWindowContent="true"' app/src/main/res/xml/accessibility_service_config.xml` | Must be `true` |
| **flagReportViewIds** | `grep "flagReportViewIds" app/src/main/res/xml/accessibility_service_config.xml` | Must be present |
| **No packageNames** | `grep "packageNames" app/src/main/res/xml/accessibility_service_config.xml` | Zero matches (exit code 1) |
| **No TYPE_VIEW_SCROLLED in config** | `grep "typeViewScrolled" app/src/main/res/xml/accessibility_service_config.xml` | Zero matches |
| **Node recycle** | `grep "recycle()" app/src/main/java/com/motherboard/focus/service/YouTubeShortsDetector.kt` | Must be present |
| **Generation token** | `grep "retryGeneration" app/src/main/java/com/motherboard/focus/service/YouTubeShortsDetector.kt` | Must be present |
| **Handler cleanup** | `grep "removeCallbacksAndMessages" app/src/main/java/com/motherboard/focus/service/YouTubeShortsDetector.kt` | Must be present |
| **Detection unconditional** | `grep -A5 "override fun onAccessibilityEvent" app/src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt` | Detector call must appear BEFORE `if (debugLogging)` check |
| **scheduleRetryOrFail** | `grep "scheduleRetryOrFail" app/src/main/java/com/motherboard/focus/service/YouTubeShortsDetector.kt` | Must be present (extracted retry helper) |
| **All three UI states** | `grep "YouTubeNotShorts" app/src/main/java/com/motherboard/focus/ui/screens/HomeScreen.kt` | Must be present (proves three-state rendering) |
| **ViewModel imports** | `grep "ShortsDetectionState" app/src/main/java/com/motherboard/focus/ui/DashboardViewModel.kt` | Must be present (proves import added) |

### Manual Test Checklist

| # | Test | Expected |
|---|---|---|
| 1 | Install app, enable Motherboard in Accessibility settings | Dashboard shows "Accessibility: Enabled" |
| 2 | Open YouTube app (home screen) | Status card shows "Watching YouTube (not Shorts)" after ~1 second (detection retries exhaust with no Shorts IDs) |
| 3 | Navigate to YouTube Shorts tab | Status card shows "Watching YouTube Shorts: Yes" within ~1 second |
| 4 | Open a normal YouTube video (not Shorts) | Status card shows "Watching YouTube (not Shorts)" |
| 5 | Open YouTube search | Status card shows "Not watching Shorts" or "Watching YouTube (not Shorts)" |
| 6 | Open YouTube subscriptions | Status card shows "Not watching Shorts" or "Watching YouTube (not Shorts)" |
| 7 | Switch from YouTube to any non-YouTube app (e.g., Settings) | Status card immediately shows "Not watching Shorts" |
| 8 | Switch back to YouTube Shorts | Status card shows "Watching YouTube Shorts: Yes" |
| 9 | Rotate phone while in Shorts | Detection state updates correctly (should remain "Yes" or briefly flicker and return) |
| 10 | Disable Motherboard in Accessibility settings | Detection subtitle disappears from status card |
| 11 | Re-enable Motherboard in Accessibility settings | Detection subtitle reappears, shows current state |
| 12 | Toggle Debug logging ON, open YouTube Shorts | logcat shows `MotherboardA11y` events with `state=YouTubeShorts` and `detection: matched` |
| 13 | Toggle Debug logging ON, open normal YouTube video | logcat shows `detection: root OK, no Shorts IDs, retries=X` then `detection: retries exhausted → YouTubeNotShorts` |
| 14 | Rapidly switch between YouTube Shorts and normal video | Detection state transitions without getting stuck in stale state |
| 15 | App does not crash during any of the above | No force-close, no ANR dialogs |
| 16 | YouTube not installed on device | App opens normally; Permissions section shows "Not enabled"; no crash |
| 17 | **EDGE CASE**: Open YouTube, wait 10 seconds before tapping Shorts tab | State transitions from `NotYouTube` → `YouTubeNotShorts` → `YouTubeShorts` correctly |
| 18 | **EDGE CASE**: Disable Motherboard service WHILE watching Shorts | Detection subtitle hides; re-enable → subtitle reappears with correct state |
| 19 | **EDGE CASE**: Rapid screen changes (YouTube home → Shorts → home → Shorts, 1 second each) | Detection state updates without stale values; no double-state glitches |

### Stop Condition

**All verification gates pass + manual tests 1-19 pass.** Stop before Phase 3 (scroll counting).

### Test Coverage Notes

The Phase 2 contract tests (PhaseTwoContractTest.kt, 22 tests) cover structure-level contracts: detector class existence, enum values, StateFlow exposure, retry parameters, Handler usage, config XML flags, full view ID qualification, recycle() calls, scheduleRetryOrFail helper, handler cleanup, three-state UI rendering, ViewModel imports, and that detection is not gated behind debugLogging. These are **structure-level** contract tests — they verify the codebase artifacts match the spec.

**Explicitly deferred to manual testing** (not in unit tests):
- Actual YouTube Shorts detection accuracy — verified via manual test (tests #2-9)
- Retry behavior with async rendering — verified via manual test (tests #3, #17)
- Dashboard reactivity to detection state changes — verified via manual test (tests #2-8, #10-11)
- Service lifecycle reset — verified via manual test (tests #10-11, #18)
- Rapid screen switching — verified via manual test (tests #14, #19)
- No crashes or ANRs — verified via all manual tests

## TODO After Phase 2 Completes

- [ ] User confirms manual test checklist (all 19 tests)
- [ ] Proceed to Phase 3: Count Shorts scrolls and show warnings (see `docs/PHASED_BUILD_AND_TEST_PLAN.md`)
