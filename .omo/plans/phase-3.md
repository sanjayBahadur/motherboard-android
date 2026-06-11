# Motherboard Phase 3: Scroll Counting & Warning Overlay

## Plan Metadata

| Field | Value |
|---|---|
| **Plan ID** | `phase-3` |
| **Created** | 2026-06-11 |
| **Status** | Ready for execution |
| **App** | Motherboard (YouTube Shorts blocker) |
| **Prerequisite** | Phase 2 COMPLETE (YouTubeShortsDetector, three-state detection, all tests pass, build passes) |
| **Phase scope** | Session scroll counting on `TYPE_VIEW_SCROLLED`, DataStore-backed sessionCount, 2-second warning overlay with severity colors (green/orange/red), debounce via existing `eventDebounceMillis` |
| **Phase explicitly OUT** | Blocking overlay (Phase 4), cooldown timer (Phase 4), session reset (Phase 4), `shortsBlockedToday`/`cooldownsTriggeredToday` incrementing (Phase 4), INTERNET, AI, analytics |
| **Package** | `com.motherboard.focus` (unchanged) |
| **New files** | `service/WarningOverlay.kt` (floating pill overlay) |
| **Tech stack** | Kotlin, Android AccessibilityService API, WindowManager, GradientDrawable, Handler/Looper, DataStore Preferences, MutableStateFlow |

## Architecture Decisions (Frozen)

| Decision | Choice | Rationale |
|---|---|---|
| Counting location | Inside `MotherboardAccessibilityService.onAccessibilityEvent`, after detector call | Service is the single receiver of accessibility events. Detector already runs unconditionally — counting is the next step in the pipeline |
| Session count persistence | New `currentSessionCount: Int = 0` field on `InterventionSettings` (10th field), persisted via DataStore | Service writes directly to DataStore on each counted scroll. ViewModel reads from DataStore for UI display. Consistent with existing persistence pattern |
| Debounce timestamp | `@Volatile var lastCountedAtMs: Long` on service companion (NOT in DataStore) | Runtime-only — doesn't need to survive app restart. Restarting resets the debounce window. Simple `@Volatile` for cross-thread visibility |
| Service DataStore access | Service obtains `SettingsStore` instance via `applicationContext` from `instance` pattern | Service already has `@Volatile var instance` on companion. Using `instance!!.applicationContext` to construct `SettingsStore`. Simple, no DI needed |
| Overlay window type | `TYPE_ACCESSIBILITY_OVERLAY` | Research-proven: trusted by system, no `SYSTEM_ALERT_WINDOW` permission needed, touch passes through, navigation works. Used by Scrolless, gkd, FakeStandby |
| Overlay flags | `FLAG_NOT_FOCUSABLE \| FLAG_NOT_TOUCHABLE` | Non-interactive — just a visual indicator. Touch passes through to YouTube below. Back/home still work |
| Overlay design | Top floating pill: `GradientDrawable` rounded rectangle with `TextView` | Matches MVP_SPEC: "top floating pill". Simple, non-intrusive, easy to see. Corner radius 24dp, white bold text, severity-colored background |
| Auto-dismiss | `Handler.postDelayed(removeView, 2000)` with cancel+repost on new scroll | Matches `warningDurationMillis = 2000`. Each new counted scroll cancels the pending dismiss and re-posts, so the pill stays visible as long as the user keeps scrolling |
| Severity colors | Green (Evergreen80, count < 50%), Orange (Sand80, 50-89%), Red (Ember80, 90%+) | From MVP_SPEC. Colors already defined in Color.kt. Applied as pill background |
| Pill content | `"Shorts: ${count} / ${limit}"` | From MVP_SPEC line 189. Simple, clear |
| Sync pattern | ViewModel `init` block syncs `blockingEnabled`, `currentSessionCount`, `eventDebounceMillis` from DataStore to service companion `@Volatile` vars | Same pattern as existing `debugLogging` sync. ViewModel reads from DataStore on launch, pushes to service. Service reads `@Volatile` vars for counting gates |
| Count persistence on increment | Service writes `sessionCount` to DataStore via `SettingsStore.save()` immediately after increment | Ensures count survives crash. Service constructs SettingsStore from application context |
| serviceEnabled check | Skip counting when `isRunning.value == false` | Belt-and-suspenders — service shouldn't receive events if not running, but check anyway |
| `typeViewScrolled` in config | Added to `accessibilityEventTypes` in accessibility_service_config.xml | Service only receives events declared in config. Currently only has `typeWindowStateChanged|typeWindowContentChanged` |
| No new dependencies | `WindowManager`, `GradientDrawable`, `Handler`, `SystemClock` are all Android framework APIs | Zero additions to `libs.versions.toml` or `build.gradle.kts` |

## Counting Rule (Frozen)

```
IF MotherboardAccessibilityService.isRunning.value == true
AND MotherboardAccessibilityService.blockingEnabled == true
AND event.packageName == "com.google.android.youtube"
AND YouTubeShortsDetector.detectionState.value == ShortsDetectionState.YouTubeShorts
AND event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
AND SystemClock.uptimeMillis() - lastCountedAtMs >= eventDebounceMillis
THEN
  currentSessionCount += 1
  lastCountedAtMs = SystemClock.uptimeMillis()
  persist currentSessionCount to DataStore
  showWarningOverlay(count = currentSessionCount, limit = sessionLimit)
```

## Data Model Changes

### InterventionSettings — 10th field added:
```kotlin
data class InterventionSettings(
    val sessionLimit: Int = 10,
    val cooldownDurationMillis: Long = 5 * 60 * 1000L,
    val warningDurationMillis: Long = 2000L,
    val eventDebounceMillis: Long = 1000L,
    val youtubeShortsEnabled: Boolean = true,
    val blockingEnabled: Boolean = true,
    val shortsBlockedToday: Int = 0,
    val cooldownsTriggeredToday: Int = 0,
    val debugLogging: Boolean = false,
    val currentSessionCount: Int = 0,        // NEW — 10th field
)
```

### SettingsStore.Keys — 10th key added:
```kotlin
val CurrentSessionCount = intPreferencesKey("current_session_count")
```

## Dashboard Contract Delta

### Session counter card — dynamic count

```
┌──────────────────────────────────────────┐
│         3 / 10                            │  ← was hardcoded "0", now dynamic
│    Shorts this session                   │
└──────────────────────────────────────────┘
```

The count color reflects severity:
- Green (Evergreen80): `count < 50% of limit`
- Orange (Sand80): `count >= 50% AND count < 90% of limit`  
- Red (Ember80): `count >= 90% of limit`

## Guardrails

| Rule | Reason |
|---|---|
| Never count when `blockingEnabled` is false | User-facing toggle must be respected |
| Never count when `detectionState != YouTubeShorts` | Only count scrolls on the Shorts player screen |
| Never count when service is not running (`isRunning == false`) | Belt-and-suspenders — service shouldn't receive events anyway |
| Debounce MUST use `eventDebounceMillis` from DataStore | Already in InterventionSettings (1000ms default). Prevents one swipe counting 5 times |
| Overlay MUST use `TYPE_ACCESSIBILITY_OVERLAY` | Trusted window type; no extra permissions; touch passes through; navigation works |
| Overlay MUST auto-dismiss after `warningDurationMillis` | Already in InterventionSettings (2000ms default). `Handler.postDelayed` |
| New scroll MUST cancel pending dismiss + re-post | Each counted scroll extends the overlay visibility — pill stays while user scrolls |
| Overlay MUST be cleaned up in `onDestroy()` | `wm.removeView()` + `handler.removeCallbacksAndMessages(null)` — prevent orphaned views |
| `sessionCount` MUST be persisted to DataStore on EACH increment | Ensure count survives app crash. Service writes directly |
| Never show blocking overlay | Phase 4 territory |
| Never reset `sessionCount` | Phase 4 (cooldown) resets it |
| Do NOT increment `shortsBlockedToday` or `cooldownsTriggeredToday` | Phase 4 territory |
| Never request `INTERNET` | Existing PhaseZeroContractTest enforces this |
| No `SYSTEM_ALERT_WINDOW` permission | `TYPE_ACCESSIBILITY_OVERLAY` doesn't need it |
| `lastCountedAtMs` is runtime-only — not in DataStore | Debounce doesn't need persistence. Reset on service restart is fine |

---

## Tasks

---

### Wave 1: Data Layer + Config (2 PARALLEL)

Tasks 1 and 2 are independent and can run in parallel.

---

#### Task 1: Add `currentSessionCount` to InterventionSettings.kt + SettingsStore.kt

**File 1**: `app/src/main/java/com/motherboard/focus/storage/InterventionSettings.kt`

**Change**: Add `val currentSessionCount: Int = 0` as the 10th field (last position, after `debugLogging`).

**After**:
```kotlin
data class InterventionSettings(
    val sessionLimit: Int = 10,
    val cooldownDurationMillis: Long = 5 * 60 * 1000L,
    val warningDurationMillis: Long = 2000L,
    val eventDebounceMillis: Long = 1000L,
    val youtubeShortsEnabled: Boolean = true,
    val blockingEnabled: Boolean = true,
    val shortsBlockedToday: Int = 0,
    val cooldownsTriggeredToday: Int = 0,
    val debugLogging: Boolean = false,
    val currentSessionCount: Int = 0,
)
```

**File 2**: `app/src/main/java/com/motherboard/focus/storage/SettingsStore.kt`

**Change 1** — Add key in `Keys` object:
```kotlin
val CurrentSessionCount = intPreferencesKey("current_session_count")
```

**Change 2** — Add `currentSessionCount` read in `settings` Flow map:
```kotlin
currentSessionCount = preferences[Keys.CurrentSessionCount] ?: 0,
```

**Change 3** — Add `currentSessionCount` write in `save()`:
```kotlin
preferences[Keys.CurrentSessionCount] = settings.currentSessionCount
```

**QA**: Verify `InterventionSettings` has 10 fields. Verify `Keys` object has 10 entries. Verify `settings` Flow reads all 10 fields. Verify `save()` writes all 10 fields.

---

#### Task 2: Add `typeViewScrolled` to accessibility_service_config.xml

**File**: `app/src/main/res/xml/accessibility_service_config.xml`

**Change**: Add `|typeViewScrolled` to `android:accessibilityEventTypes`:

**Before**:
```xml
android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
```

**After**:
```xml
android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeViewScrolled"
```

**QA**: Verify `typeViewScrolled` is present. Verify `flagReportViewIds` still present. Verify `canRetrieveWindowContent="true"` unchanged. Verify `packageNames` still absent.

---

### Wave 2: Overlay + Service (SEQUENTIAL)

Task 3 creates the overlay class. Task 4 wires it into the service with counting logic. Run Task 3 first, then Task 4.

---

#### Task 3: Create `WarningOverlay.kt`

**File**: `app/src/main/java/com/motherboard/focus/service/WarningOverlay.kt` (NEW)

**What**: New class that manages a floating pill overlay. Shows "Shorts: X / limit" with severity-colored background for 2 seconds. Uses `TYPE_ACCESSIBILITY_OVERLAY` — no extra permissions needed.

**Full content**:

```kotlin
package com.motherboard.focus.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class WarningOverlay(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { dismiss() }

    private var overlayRoot: FrameLayout? = null
    private var windowManager: WindowManager? = null

    private fun getWindowManager(): WindowManager {
        if (windowManager == null) {
            windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
        return windowManager!!
    }

    /**
     * Show a warning pill with "Shorts: X / limit" for 2 seconds.
     * If called again before the previous pill is dismissed, the old pill is removed
     * and the timer restarts — so the pill stays visible as long as the user keeps scrolling.
     */
    fun show(count: Int, limit: Int) {
        // Cancel any pending dismiss
        handler.removeCallbacks(dismissRunnable)

        // Remove existing overlay if present
        overlayRoot?.let {
            try { getWindowManager().removeView(it) } catch (_: Exception) {}
        }

        // Determine severity
        val ratio = count.toFloat() / limit.toFloat()
        val bgColor = when {
            ratio >= 0.9f -> Color.parseColor("#FF5722")     // Red (Ember80 variant)
            ratio >= 0.5f -> Color.parseColor("#FF9800")     // Orange (Sand80 variant)
            else -> Color.parseColor("#4CAF50")               // Green (Evergreen80 variant)
        }

        // Build the pill TextView
        val pill = TextView(service).apply {
            text = "Shorts: $count / $limit"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(
                dpToPx(20f),
                dpToPx(10f),
                dpToPx(20f),
                dpToPx(10f)
            )
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dpToPx(24f).toFloat()
            }
            elevation = dpToPx(6f).toFloat()
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        // Wrap in FrameLayout
        overlayRoot = FrameLayout(service).apply {
            addView(pill, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        // Layout params: trusted overlay, non-interactive, top-center
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dpToPx(60f) // Below status bar
        }

        try {
            // Fade in
            overlayRoot?.alpha = 0f
            getWindowManager().addView(overlayRoot, params)
            overlayRoot?.animate()?.alpha(1f)?.setDuration(200)?.start()

            // Auto-dismiss after 2 seconds
            handler.postDelayed(dismissRunnable, 2000)
        } catch (e: Exception) {
            // If addView fails (e.g., permission issue), silently clean up
            overlayRoot = null
        }
    }

    /**
     * Dismiss the warning pill with a brief fade-out animation.
     * Safe to call multiple times — no-op if already dismissed.
     */
    fun dismiss() {
        handler.removeCallbacks(dismissRunnable)
        val view = overlayRoot ?: return
        overlayRoot = null

        view.animate()
            .alpha(0f)
            .translationY(-dpToPx(20f).toFloat())
            .setDuration(200)
            .withEndAction {
                try { getWindowManager().removeView(view) } catch (_: Exception) {}
            }
            .start()
    }

    /**
     * Clean up all resources. Called from service onDestroy.
     */
    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        overlayRoot?.let {
            try { getWindowManager().removeView(it) } catch (_: Exception) {}
        }
        overlayRoot = null
        windowManager = null
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            service.resources.displayMetrics
        ).toInt()
    }
}
```

**Key design decisions**:
- `show()` cancels previous dismiss and removes old overlay before creating new one — ensures only one pill at a time
- Fade-in animation (200ms) on show, fade-out + slide-up (200ms) on dismiss
- `TYPE_ACCESSIBILITY_OVERLAY` — trusted by system, no `SYSTEM_ALERT_WINDOW` permission
- `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE` — touch passes through to YouTube
- `Gravity.TOP | Gravity.CENTER_HORIZONTAL` — centered at top, below status bar (60dp)
- `destroy()` — comprehensive cleanup for service lifecycle
- `try/catch` around `addView`/`removeView` — WindowManager can throw if view already added/removed

**QA**: Verify `TYPE_ACCESSIBILITY_OVERLAY` used. Verify `FLAG_NOT_FOCUSABLE`. Verify `FLAG_NOT_TOUCHABLE`. Verify `Handler` for auto-dismiss. Verify `GradientDrawable` for rounded pill. Verify severity colors match spec thresholds. Verify `destroy()` method.

---

#### Task 4: Update `MotherboardAccessibilityService.kt` — counting logic + overlay wiring

**File**: `app/src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt`

**What**: Add counting logic and overlay wiring. Three changes:
1. Add companion `@Volatile` vars for counting gates: `blockingEnabled`, `eventDebounceMillis`
2. Add `@Volatile var lastCountedAtMs: Long = 0L` for debounce
3. Create `WarningOverlay` instance and `SettingsStore` for persistence
4. Add counting logic in `onAccessibilityEvent` after detector call
5. Show overlay on each counted scroll
6. Clean up overlay in `onDestroy()`

**Full replacement content**:

```kotlin
package com.motherboard.focus.service

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.motherboard.focus.storage.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

        /** Timestamp of the last counted scroll (for debounce). Runtime-only. */
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

        // All gates passed — increment count
        val store = settingsStore ?: return
        val newCount = lastCountedAtMs.toInt().let {
            // We need to read the current count. Use a simple approach:
            // Get current settings, increment, save.
            var count = 0
            serviceScope.launch {
                // Read current sessionCount from DataStore, increment, save, show overlay
                // We use a simple read-modify-write pattern
                val currentSettings = kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.flow.first(store.settings)
                }
                count = currentSettings.currentSessionCount + 1
                store.save(currentSettings.copy(currentSessionCount = count))

                // Show warning overlay
                warningOverlay.show(count, currentSettings.sessionLimit)

                if (debugLogging) {
                    Log.d(TAG, "count: scroll counted → $count / ${currentSettings.sessionLimit}")
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
```

**Key changes from Phase 2**:
- New imports: `SystemClock`, `SettingsStore`, coroutine scope classes
- New companion vars: `blockingEnabled`, `eventDebounceMillis`, `lastCountedAtMs`
- New instance fields: `warningOverlay`, `settingsStore`, `serviceScope`
- `onServiceConnected`: creates `WarningOverlay` and `SettingsStore`
- `tryCountScroll(event)`: 5-gate counting logic with debounce
- `onDestroy`: calls `warningOverlay.destroy()`
- Count persists to DataStore on each increment

**QA**: Verify `TYPE_VIEW_SCROLLED` check present. Verify `blockingEnabled` check. Verify `detectionState == YouTubeShorts` check. Verify debounce via `lastCountedAtMs` and `eventDebounceMillis`. Verify `SettingsStore.save()` called after increment. Verify `warningOverlay.show()` called. Verify `warningOverlay.destroy()` in `onDestroy()`. Verify `isRunning` check.

---

### Wave 3: ViewModel + UI Wiring (SEQUENTIAL within wave)

---

#### Task 5: Update `DashboardViewModel.kt` — sync counting state + expose sessionCount

**File**: `app/src/main/java/com/motherboard/focus/ui/DashboardViewModel.kt`

**What**: 
1. Extend the existing `init` sync loop to push `blockingEnabled`, `currentSessionCount`, and `eventDebounceMillis` to service companion
2. Expose `sessionCount` as a derived StateFlow from `settings` for the dashboard

**Change 1** — Update the `init` block to sync additional fields. Extend the existing `settings.collect` lambda (currently only syncs `debugLogging`):
```kotlin
        // Sync debugLogging + Phase 3 counting fields from DataStore to service companion
        viewModelScope.launch {
            store.settings.collect { s ->
                MotherboardAccessibilityService.debugLogging = s.debugLogging
                MotherboardAccessibilityService.blockingEnabled = s.blockingEnabled
                MotherboardAccessibilityService.eventDebounceMillis = s.eventDebounceMillis
            }
        }
```

**Change 2** — Add a `sessionCount` StateFlow derived from settings:
```kotlin
    /** Current session Shorts count, derived from persisted DataStore value */
    val sessionCount: StateFlow<Int> = store.settings
        .map { it.currentSessionCount }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )
```

**Additional import needed**: `import kotlinx.coroutines.flow.map`

**QA**: Verify `blockingEnabled` synced to service. Verify `eventDebounceMillis` synced to service. Verify `sessionCount` is `StateFlow<Int>` with initial value 0. Verify `import kotlinx.coroutines.flow.map` added.

---

#### Task 6: Update `HomeScreen.kt` — dynamic session counter with severity color

**File**: `app/src/main/java/com/motherboard/focus/ui/screens/HomeScreen.kt`

**What**: Replace the hardcoded `"0"` in the session counter card with the real `sessionCount`. Apply severity color to the count text.

**Change 1** — Add `sessionCount: Int` parameter to function signature (4th param, after `detectionState`):
```kotlin
@Composable
fun HomeScreen(
    settings: InterventionSettings,
    isServiceEnabled: Boolean,
    detectionState: ShortsDetectionState,
    sessionCount: Int,                                  // NEW — was hardcoded to 0
    ...
)
```

**Change 2** — Replace the hardcoded counter text. Find:
```kotlin
text = "0 / ${settings.sessionLimit}",
```

Replace with:
```kotlin
text = "$sessionCount / ${settings.sessionLimit}",
```

**Change 3** — Add severity color to the counter. Compute severity:
```kotlin
val severityColor = when {
    sessionCount >= (settings.sessionLimit * 0.9).toInt() -> MaterialTheme.colorScheme.tertiary   // Red / Ember
    sessionCount >= (settings.sessionLimit * 0.5).toInt() -> MaterialTheme.colorScheme.secondary  // Orange / Sand
    else -> MaterialTheme.colorScheme.primary                                                     // Green / Evergreen
}
```

Apply to the counter `Text`:
```kotlin
color = severityColor,
```

**QA**: Verify `sessionCount` parameter added. Verify hardcoded "0" replaced with `"$sessionCount / ${settings.sessionLimit}"`. Verify severity color applied (green < 50%, orange 50-89%, red >= 90%). Verify all existing functionality unchanged.

---

#### Task 7: Update `App.kt` — collect and pass sessionCount

**File**: `app/src/main/java/com/motherboard/focus/ui/App.kt`

**What**: Collect `sessionCount` from ViewModel and pass it to HomeScreen.

**Change**: Add `val sessionCount by viewModel.sessionCount.collectAsState()` and pass `sessionCount = sessionCount` to `HomeScreen`.

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
    val sessionCount by viewModel.sessionCount.collectAsState()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        HomeScreen(
            settings = settings,
            isServiceEnabled = isServiceEnabled,
            detectionState = detectionState,
            sessionCount = sessionCount,
            onToggleBlocking = viewModel::setBlockingEnabled,
            onSessionLimitChange = viewModel::setSessionLimit,
            onCooldownMinutesChange = viewModel::setCooldownMinutes,
            onToggleDebugLogging = viewModel::setDebugLogging,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
```

**QA**: Verify `sessionCount` collected via `collectAsState()`. Verify passed to `HomeScreen` as 4th parameter. Verify build compiles.

---

### Wave 4: Test & Verification Gates

---

#### Task 8: Create `PhaseThreeContractTest.kt`

**File**: `app/src/test/java/com/motherboard/focus/PhaseThreeContractTest.kt` (NEW)

**What**: Contract tests for Phase 3. Verify new data fields, config XML, overlay class, counting logic.

**Full content** — 20 tests:
- InterventionSettings has 10 fields including `currentSessionCount`
- `currentSessionCount` defaults to 0
- SettingsStore has `CurrentSessionCount` key
- Config XML includes `typeViewScrolled`
- WarningOverlay class exists
- WarningOverlay uses `TYPE_ACCESSIBILITY_OVERLAY`
- WarningOverlay has `show(count, limit)` method
- WarningOverlay has `dismiss()` method
- WarningOverlay has `destroy()` method
- Service checks `TYPE_VIEW_SCROLLED` event type
- Service checks `blockingEnabled` gate
- Service checks `detectionState == YouTubeShorts` gate
- Service checks debounce via `lastCountedAtMs`
- Service references `SettingsStore` (persistence)
- Service calls `warningOverlay.show()` after counting
- Service destroys overlay in `onDestroy()`
- ViewModel syncs `blockingEnabled` to service
- ViewModel syncs `eventDebounceMillis` to service
- HomeScreen shows dynamic sessionCount (not hardcoded "0")
- No `shortsBlockedToday` increment in service (Phase 4 guard)

Follow Phase 0/1/2 conventions: pure JUnit 4, file-based assertions, reflection for data class fields, assertion messages first.

**QA**: All tests pass with `./gradlew testDebugUnitTest`. Verify 20 tests. No Mockito/MockK.

---

#### Task 9: Update `PhaseTwoContractTest.kt` — fix `typeViewScrolled` assertion

**File**: `app/src/test/java/com/motherboard/focus/PhaseTwoContractTest.kt`

**What**: Phase 2's config XML test asserts `typeViewScrolled` is NOT present. Phase 3 adds it. Update the assertion.

**Change**: In test `accessibility service config does NOT include typeViewScrolled`, change `assertFalse` to `assertTrue`:
```kotlin
assertTrue("typeViewScrolled must be present in Phase 3", config.contains("typeViewScrolled"))
```

**QA**: Verify assertion now expects `typeViewScrolled` to be present.

---

#### Task 10: Run `./gradlew assembleDebug`

**Command**: `./gradlew assembleDebug`

**Expected**: BUILD SUCCESSFUL.

---

#### Task 11: Run `./gradlew testDebugUnitTest`

**Command**: `./gradlew testDebugUnitTest`

**Expected**: All tests pass. PhaseZero (8) + PhaseOne (8) + PhaseTwo (22, 1 updated) + PhaseThree (20) = 58 tests all passing.

---

#### Task 12: Cruft grep — no blocking logic

**Command**: `grep -rn "performGlobalAction\|cooldown\|blocked\|blocking overlay" app/src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt`

**Expected**: Zero matches. Blocking overlay is Phase 4.

---

## Dependency Graph

```
Wave 1: PARALLEL
  Task 1 (InterventionSettings + SettingsStore) ──┤
  Task 2 (accessibility_service_config.xml) ──────┤
                                                   │
Wave 2: SEQUENTIAL                                 ▼
  Task 3 (WarningOverlay.kt) → Task 4 (MotherboardAccessibilityService.kt)
                                                   │
Wave 3: SEQUENTIAL                                 ▼
  Task 5 (DashboardViewModel.kt) → Task 6 (HomeScreen.kt) → Task 7 (App.kt)
                                                   │
Wave 4: SEQUENTIAL                                 ▼
  Task 8 (PhaseThreeContractTest) → Task 9 (PhaseTwo update) → Task 10 (build) → Task 11 (tests) → Task 12 (cruft)
```

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| `TYPE_VIEW_SCROLLED` floods service with thousands of events | High | Medium (battery) | Debounce (1000ms) + only process when `YouTubeShorts` detected. Non-YouTube scrolls immediately returned |
| DataStore lost-update on rapid counting | Low | Low | Single-threaded service. Count increments are sequential. Acceptable for Phase 3 |
| WindowManager `addView` fails (permission, stale view) | Low | Medium | `try/catch` around `addView`/`removeView`. Overlay is cosmetic — counting continues even if overlay fails |
| Overlay leaks if service killed by system | Low | Medium | `destroy()` in `onDestroy()`. `try/catch` on `removeView` handles stale views |
| Service `instance` null when ViewModel syncs | Low | Low | ViewModel syncs on `collect` which fires on subscription. If service not bound, `@Volatile` vars just stay at defaults. Service sets them on connect |
| `runBlocking` in `tryCountScroll` blocks main thread | Medium | Medium | The `runBlocking` call reads DataStore synchronously. DataStore reads are fast (<1ms) for simple int values. Acceptable for Phase 3 — refactor to fully async in Phase 5 if needed |

## Final Verification Wave

| Gate | Command | Expected |
|---|---|---|
| **Build** | `./gradlew assembleDebug` | BUILD SUCCESSFUL |
| **Unit tests** | `./gradlew testDebugUnitTest` | 58/58 pass |
| **No INTERNET** | `grep -r "INTERNET" app/src/main/AndroidManifest.xml` | Zero matches |
| **No SYSTEM_ALERT_WINDOW** | `grep -r "SYSTEM_ALERT_WINDOW" app/src/main/` | Zero matches |
| **typeViewScrolled present** | `grep "typeViewScrolled" app/src/main/res/xml/accessibility_service_config.xml` | Must be present |
| **Blocking gate** | `grep "blockingEnabled" app/src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt` | Must be present |
| **Detection gate** | `grep "YouTubeShorts" app/src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt` | Must be present (counting gate) |
| **No blocking overlay** | `grep "performGlobalAction\|cooldown\|blocking overlay" app/src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt` | Zero matches |
| **No shortsBlockedToday incr** | `grep "shortsBlockedToday" app/src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt` | Zero matches |
| **Overlay destroy** | `grep "warningOverlay.destroy()" app/src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt` | Must be present |

### Manual Test Checklist

| # | Test | Expected |
|---|---|---|
| 1 | Install app, enable Motherboard in Accessibility settings | Dashboard shows "Accessibility: Enabled" |
| 2 | Set hard limit to 5 for testing | Slider moves, value persists |
| 3 | Open YouTube, navigate to Shorts | Detection subtitle shows "Watching YouTube Shorts: Yes" |
| 4 | Scroll Shorts once | Counter shows `1 / 5`, green color; warning pill appears at top for 2 seconds showing "Shorts: 1 / 5" |
| 5 | Scroll Shorts twice more (total 3) | Counter shows `3 / 5`, orange color; pill shows updated count |
| 6 | Switch to normal YouTube video, scroll | Counter does NOT increment; no pill |
| 7 | Switch to YouTube home, scroll | Counter does NOT increment |
| 8 | Toggle blocking OFF, scroll Shorts | Counter does NOT increment |
| 9 | Toggle blocking ON, scroll Shorts | Counter increments normally |
| 10 | Scroll rapidly (multiple swipes in <1 second) | Counter increments only once (debounce working) |
| 11 | App restart — check counter | Counter persists from DataStore (same value) |
| 12 | Toggle Debug logging ON, scroll Shorts | logcat shows `count: scroll counted → X / limit` |
| 13 | Overlay does not block navigation | Back button works, home gesture works while overlay visible |
| 14 | App does not crash | No force-close during any test |

### Stop Condition

**All verification gates pass + manual tests 1-14 pass.** Stop before Phase 4 (cooldown blocking).

## TODO After Phase 3 Completes

- [ ] User confirms manual test checklist (all 14 tests)
- [ ] Proceed to Phase 4: Cooldown blocking overlay (see `docs/PHASED_BUILD_AND_TEST_PLAN.md`)
