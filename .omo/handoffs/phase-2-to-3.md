# Handoff: Phase 2 Complete → Phase 3 Planning

**Date**: 2026-06-11  
**From**: Atlas (orchestrator, ses_14b2dab2affeKJlZyvwFpRejQC)  
**To**: Next session (Atlas or Sisyphus)  
**Git HEAD**: `8009b36` — Finalize Phase 2 boulder state

---

## What Phase 2 Delivered

**Commit range**: `6ce75a1` → `8009b36` (3 commits)

| File | Action | Purpose |
|---|---|---|
| `service/YouTubeShortsDetector.kt` | NEW (174 lines) | Shorts detection via AccessibilityNodeInfo |
| `service/MotherboardAccessibilityService.kt` | MODIFIED | Wired detector unconditionally |
| `res/xml/accessibility_service_config.xml` | MODIFIED | `canRetrieveWindowContent=true`, `flagReportViewIds`, removed `packageNames` |
| `ui/DashboardViewModel.kt` | MODIFIED | Exposes `detectionState: StateFlow<ShortsDetectionState>` |
| `ui/screens/HomeScreen.kt` | MODIFIED | Three-state detection subtitle in status card |
| `ui/App.kt` | MODIFIED | Collects and passes `detectionState` |
| `test/PhaseTwoContractTest.kt` | NEW (22 tests) | Contract tests for Phase 2 artifacts |
| `test/PhaseOneContractTest.kt` | MODIFIED | Updated stale assertions |

**Verification**: 38/38 tests pass, `./gradlew assembleDebug` SUCCESSFUL.

### Key architectural decisions from Phase 2:

1. **Detection state enum**: `NotYouTube` / `YouTubeNotShorts` / `YouTubeShorts` — exposed via `YouTubeShortsDetector.Companion.detectionState: MutableStateFlow`
2. **Retry pattern**: `Handler.postDelayed`, 300ms × 3 attempts, generation token for cancellation
3. **Handler cleanup**: `handler.removeCallbacksAndMessages(null)` in `reset()` 
4. **Node recycling**: All `AccessibilityNodeInfo` objects recycled in `finally` blocks
5. **Detection is UNCONDITIONAL**: Not gated by `debugLogging` — only `Log.d()` calls are
6. **Package filter removed**: `android:packageNames` removed from XML; code-level filter in detector instead
7. **View IDs**: `com.google.android.youtube:id/reel_player_page_container`, `reel_recycler`, `reel_progress_bar`
8. **State sharing pattern**: Companion `MutableStateFlow` on `YouTubeShortsDetector`, observed by `DashboardViewModel` via `stateIn(WhileSubscribed(5000))`

---

## Current Codebase State (Post Phase 2)

```
app/src/main/java/com/motherboard/focus/
├── MainActivity.kt                          (23 lines)
├── service/
│   ├── MotherboardAccessibilityService.kt   (91 lines) ← counting hook here
│   └── YouTubeShortsDetector.kt             (174 lines) ← detection state here
├── storage/
│   ├── InterventionSettings.kt              (13 lines, 9 fields) ← needs 2 new fields
│   └── SettingsStore.kt                     (58 lines, 9 keys) ← needs 2 new keys
└── ui/
    ├── App.kt                               (30 lines) ← needs sessionCount param
    ├── DashboardViewModel.kt                (104 lines) ← needs sessionCount StateFlow
    ├── screens/
    │   └── HomeScreen.kt                    (288 lines) ← counter hardcoded to "0"
    └── theme/
        ├── Color.kt                         (11 lines) ← Evergreen80/Sand80/Ember80 ready
        ├── Theme.kt                         (22 lines)
        └── Type.kt                          (5 lines)
```

**Overlay infrastructure**: NONE. Zero existing overlay/window classes. Must be built from scratch.

---

## Phase 3 Requirements (from `docs/PHASED_BUILD_AND_TEST_PLAN.md`)

> Implement Shorts scroll counting and warning overlay:
> - increment session count only on debounced `TYPE_VIEW_SCROLLED` events while `YouTubeShortsDetector` says `YouTubeShorts`
> - persist session count with DataStore
> - show a 2-second warning overlay after each counted scroll
> - warning shows "Shorts: X / limit"
> - warning uses green, orange, or red severity
> - no blocking overlay yet

### Counting rule (from MVP_SPEC.md):

```
If blocking enabled
AND service enabled
AND package is com.google.android.youtube
AND screen state is YouTubeShorts
AND event type is TYPE_VIEW_SCROLLED
AND now - lastCountedAt >= debounceMs
THEN sessionCount += 1
```

### Warning colors (from MVP_SPEC.md):

- Green (Evergreen80): `count < 50% of limit`
- Orange (Sand80): `count >= 50% && count < 90% of limit`
- Red (Ember80): `count >= 90% of limit`

### Warning content: `"Shorts: 7 / 10"`

### Design: Top floating pill or screen border

---

## Overlay Research Findings

**Recommended pattern**: `TYPE_ACCESSIBILITY_OVERLAY` (no `SYSTEM_ALERT_WINDOW` permission needed). This is the golden window type — trusted by system, passes touch through, keeps Android navigation working.

**Key flags for non-interactive warning pill**:
```kotlin
type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
flags = FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE
format = PixelFormat.TRANSLUCENT
gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
```

**Timed auto-dismiss pattern** (from Scrolless/FakeStandby production code):
```kotlin
private val handler = Handler(Looper.getMainLooper())
private val dismissRunnable = Runnable { dismissOverlay() }

fun showWarning() {
    dismissOverlay()  // remove existing
    overlayView = createPill(severity)
    wm.addView(overlayView, layoutParams)
    handler.postDelayed(dismissRunnable, 2000)
}

fun dismissOverlay() {
    handler.removeCallbacks(dismissRunnable)
    overlayView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
    overlayView = null
}
```

**Pill styling**: `GradientDrawable` with `cornerRadius=24dp`, white bold text, background color determined by severity. `TextView` with `elevation=6f` for shadow.

**Lifecycle**: Create overlay in `onServiceConnected()`, clean up in `onDestroy()`.

---

## Phase 3 Implementation Delta (Scaffold)

### Data Model Changes

**InterventionSettings.kt** — add 2 new fields (fields 10 and 11):
```kotlin
val currentSessionCount: Int = 0,          // NEW — persists across app restarts
val lastCountedAtMillis: Long = 0L,        // NEW — debounce timestamp
```

**SettingsStore.kt** — add 2 new keys + read/write:
```kotlin
val CurrentSessionCount = intPreferencesKey("current_session_count")
val LastCountedAtMillis = longPreferencesKey("last_counted_at_millis")
```

### Config XML Change

**accessibility_service_config.xml** — add `|typeViewScrolled` to event types:
```xml
android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeViewScrolled"
```

### New File: WarningOverlay.kt

New class in `com.motherboard.focus.service` — manages the floating pill overlay:
- `show(count: Int, limit: Int)` — creates/updates the pill with appropriate severity color
- `dismiss()` — removes from WindowManager after 2 seconds
- Uses `TYPE_ACCESSIBILITY_OVERLAY`, `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`
- Pill: rounded `GradientDrawable` with severity background, white `"Shorts: X / limit"` text

### Service Changes

**MotherboardAccessibilityService.kt** — add counting logic in `onAccessibilityEvent`:
1. After detector call, check if `event.eventType == TYPE_VIEW_SCROLLED`
2. Check `YouTubeShortsDetector.detectionState.value == YouTubeShorts`
3. Check `blockingEnabled` (via companion `@Volatile var`)
4. Check debounce: `SystemClock.uptimeMillis() - lastCountedAt >= eventDebounceMillis`
5. If all pass: increment sessionCount, update companion, persist to DataStore, show overlay

**New companion fields needed**:
```kotlin
@Volatile var blockingEnabled: Boolean = true    // synced from DataStore by ViewModel
@Volatile var sessionCount: Int = 0              // synced from DataStore by ViewModel
@Volatile var eventDebounceMillis: Long = 1000L  // synced from DataStore by ViewModel
```

**ViewModel sync** (existing pattern from `debugLogging`):
```kotlin
// In DashboardViewModel.init {}:
viewModelScope.launch {
    store.settings.collect { s ->
        MotherboardAccessibilityService.blockingEnabled = s.blockingEnabled
        MotherboardAccessibilityService.sessionCount = s.currentSessionCount
        MotherboardAccessibilityService.eventDebounceMillis = s.eventDebounceMillis
    }
}
```

### ViewModel + UI Changes

**DashboardViewModel.kt** — add `sessionCount: StateFlow<Int>` from DataStore. Compute severity as derived value.

**HomeScreen.kt** — change hardcoded `"0"` to `"${sessionCount}"`. Add `sessionCount` parameter. Optionally show severity indicator color on the counter text.

**App.kt** — collect `sessionCount` and pass to HomeScreen.

### Test Changes

**PhaseThreeContractTest.kt** (NEW) — contract tests for:
- `currentSessionCount` and `lastCountedAtMillis` fields on InterventionSettings
- `CurrentSessionCount` and `LastCountedAtMillis` keys in SettingsStore
- `typeViewScrolled` in config XML
- `WarningOverlay` class existence
- `TYPE_ACCESSIBILITY_OVERLAY` usage in overlay
- `if (blockingEnabled)` and `if (detectionState == YouTubeShorts)` gates in service
- `recycle()` for nodeInfo objects in overlay (if any)
- No blocking logic (Phase 4 guard)

**PhaseTwoContractTest.kt** — update assertions:
- `canRetrieveWindowContent="true"` unchanged (still true)
- `flagReportViewIds` unchanged
- `no packageNames` unchanged
- `no typeViewScrolled` → NOW PRESENT (update this assertion)

### Task Dependency Order

```
Wave 1 (PARALLEL):
  Task 1: Add fields to InterventionSettings.kt + SettingsStore.kt
  Task 2: Add typeViewScrolled to config XML

Wave 2 (SEQUENTIAL):
  Task 3: Create WarningOverlay.kt (needs nothing from Wave 1)
  Task 4: Update MotherboardAccessibilityService.kt (needs Tasks 1+2+3)
  Task 5: Update DashboardViewModel.kt (needs Task 1)

Wave 3 (SEQUENTIAL):
  Task 6: Update HomeScreen.kt (needs Task 5)
  Task 7: Update App.kt (needs Tasks 5+6)

Wave 4 (Tests):
  Task 8: Create PhaseThreeContractTest.kt
  Task 9: Update PhaseTwoContractTest.kt
  Task 10: Build verification
  Task 11: Unit test run
```

---

## Guardrails for Phase 3

| Rule | Reason |
|---|---|
| Never increment counter when `blockingEnabled` is false | User-facing toggle must be respected |
| Never increment counter when service is not enabled | Meaningless — service won't receive events anyway, but belt-and-suspenders |
| Never increment counter when `detectionState != YouTubeShorts` | Only count scrolls on the Shorts player screen |
| Debounce MUST use `eventDebounceMillis` from DataStore (1000ms default) | Already in InterventionSettings. Prevents one swipe counting 5 times |
| Overlay MUST use `TYPE_ACCESSIBILITY_OVERLAY` | No extra permissions, touch passes through, navigation works |
| Overlay MUST auto-dismiss after `warningDurationMillis` (2000ms default) | Already in InterventionSettings |
| Overlay MUST be cleaned up in `onDestroy()` | Prevent orphaned views |
| Never show blocking overlay | Phase 4 territory |
| Never reset sessionCount | Phase 4 (cooldown) resets it |
| Do not increment `shortsBlockedToday` or `cooldownsTriggeredToday` | Phase 4 territory |
| No new dependencies | `WindowManager`, `GradientDrawable`, `Handler` are all framework APIs |
| No INTERNET permission | Existing PhaseZeroContractTest enforces this |

---

## Risks

| Risk | Mitigation |
|---|---|
| `TYPE_VIEW_SCROLLED` floods the service with events | Debounce (1000ms default) + only process when `detectionState == YouTubeShorts` |
| Overlay leaks if service killed | Cleanup in `onDestroy()`. Also try/catch around `wm.removeView()` for stale views |
| DataStore lost-update on rapid counting | Single-threaded service. Acceptable for Phase 3 — add `Mutex` in Phase 5 if needed |
| YouTube changes view IDs | Already documented — detection uses view IDs from Phase 2; counting only gates on detection state |
| `sessionCount` sync race: service increments before ViewModel syncs | Service uses `@Volatile var` directly; ViewModel syncs on init. If service increments before ViewModel starts collecting, the ViewModel reads stale value then rebases from DataStore. Acceptable: ViewModel's DataStore read wins |

---

## Quick Start for Next Session

```
/start-work phase-3
```

If no phase-3 plan exists yet, create it using this handoff as the scaffold. The full research is captured above — the plan can be generated directly from this document + `docs/PHASED_BUILD_AND_TEST_PLAN.md` + `docs/MVP_SPEC.md`.
