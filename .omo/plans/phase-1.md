# Motherboard Phase 1: AccessibilityService Skeleton

## Plan Metadata

| Field | Value |
|---|---|
| **Plan ID** | `phase-1` |
| **Created** | 2026-06-09 |
| **Status** | Ready for execution |
| **App** | Motherboard (YouTube Shorts blocker) |
| **Prerequisite** | Phase 0 COMPLETE (all 19 tasks done, build passes, tests pass) |
| **Phase scope** | AccessibilityService skeleton: service class, manifest, config XML, YouTube filter, debug logging, dashboard service status |
| **Phase explicitly OUT** | Shorts detection (Phase 2), counting (Phase 3), overlays (Phase 3-4), blocking (Phase 4), INTERNET, AI, analytics |
| **Package** | `com.motherboard.focus` (unchanged) |
| **New sub-package** | `com.motherboard.focus.service` |
| **Tech stack** | Kotlin, Android AccessibilityService API, ContentObserver, MutableStateFlow |

## Architecture Decisions (Frozen)

| Decision | Choice | Rationale |
|---|---|---|
| Service package | `com.motherboard.focus.service` | Matches existing sub-package convention (storage/, ui/, ui/screens/, ui/theme/) |
| State sharing | Companion `MutableStateFlow<Boolean>` + `@Volatile var instance` | Production pattern from gkd-kit (38K stars). Same process — no IPC needed |
| Service enabled detection | ContentObserver on `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` | User chose real-time detection. Registered in DashboardViewModel, lifecycle-aware cleanup via `viewModelScope` |
| Debug flag | User-facing toggle, persisted in DataStore as `debugLogging: Boolean = false` | User chose explicit control. New 9th field in InterventionSettings + SettingsStore |
| Debug flag sharing | `@Volatile var` on service companion, set by ViewModel from DataStore read | Same process, simple. Service checks `companion.debugLogging` before `Log.d()` |
| Debug flag default | `false` (off) | Privacy-by-default. User must opt in to event logging |
| Service state persistence | Runtime only (no DataStore key) | Service enabled/disabled is system-managed, not app-persisted |
| Event types in config | `typeWindowStateChanged\|typeWindowContentChanged` | Minimal for Phase 1. `TYPE_VIEW_SCROLLED` added in Phase 3 |
| canRetrieveWindowContent | `false` in Phase 1 | Not needed for logging. Set to `true` in Phase 2 (Shorts detection) |
| notificationTimeout | 300ms | Standard throttling value from Android docs |
| Accessibility description | Text from MVP_SPEC.md lines 257-265 | Already specified in spec — explains why permission is needed, what data is NOT collected |
| Package filter | `com.google.android.youtube` in config XML `android:packageNames` | Specified in PHASED_BUILD_AND_TEST_PLAN. Service only receives YouTube events |
| Logging privacy | Log event type, package name, class name only — NEVER log text content | Prevents accidental raw text storage per README constraint |
| No new dependencies | AccessibilityService is framework API, ContentObserver is part of `android.database` | Zero additions to `libs.versions.toml` or `build.gradle.kts` |

## Guardrails

| Rule | Reason |
|---|---|
| Never log `event.text` or `event.contentDescription` | README constraint: "Do not store raw accessibility text" |
| Never call `rootInActiveWindow` in Phase 1 | Requires `canRetrieveWindowContent=true` which is Phase 2 |
| Never increment counters or modify state in `onAccessibilityEvent` | That's Phase 3 territory |
| Never add overlay views from the service | That's Phase 3-4 territory |
| ContentObserver MUST be unregistered in `onCleared()` | Prevents memory leaks |
| `onAccessibilityEvent` MUST handle `null` events gracefully | Android can deliver null events |
| Manifest MUST NOT declare `INTERNET` | Existing PhaseZeroContractTest enforces this |
| `BuildConfig.DEBUG` is NOT used for logging gate | User explicitly chose user-facing toggle; use `companion.debugLogging` only |
| Service-start-before-app: `debugLogging` defaults to `false` until ViewModel syncs | Privacy-by-default. ViewModel's `init` block pushes persisted value to `@Volatile var` on launch. If service binds before app opens, logging stays off until app launches. Accepted for Phase 1. |

## Data Model Changes

### InterventionSettings — 9th field added:
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
    val debugLogging: Boolean = false,      // NEW — defaults OFF
)
```

### SettingsStore.Keys — 9th key added:
```kotlin
val DebugLogging = booleanPreferencesKey("debug_logging")
```

## Dashboard Contract Delta

### Always Visible (unchanged from Phase 0)

```
┌──────────────────────────────────────┐
│  Motherboard                          │
│  Shorts blocker for your attention... │
├──────────────────────────────────────┤
│  YouTube Shorts Blocking        [ON]  │
├──────────────────────────────────────┤
│         0 / 10                        │
│    Shorts this session               │
└──────────────────────────────────────┘
```

### Expandable Section 1: Stats (unchanged)

### Expandable Section 2: Settings — new toggle added

```
▼ Settings
  Hard limit: 10 Shorts          [Slider 3..50]
  Cooldown: 5 minutes            [Slider 1..30]
  Debug logging                [Switch OFF]     ← NEW
```

### Expandable Section 3: Permissions — now reactive

```
▼ Permissions
  Accessibility: Not enabled                      ← REACTIVE (was hardcoded)
                                  or
  Accessibility: Enabled       (green text)       ← NEW state
  [Open Accessibility Settings]                    ← unchanged
```

---

## Tasks

---

### Wave 1: Data Layer & Config (3 PARALLEL + 1 sequential)

Tasks 1, 3, and 4 are independent and can run in parallel. Task 2 depends on Task 1 (needs `debugLogging` field on `InterventionSettings`) and must run after it.

---

#### Task 1: Add `debugLogging` field to InterventionSettings.kt

**File**: `app/src/main/java/com/motherboard/focus/storage/InterventionSettings.kt`

**Change**: Add `val debugLogging: Boolean = false` as the 9th field (last position, after `cooldownsTriggeredToday`).

**Before (lines 3-12)**:
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
)
```

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
)
```

**QA**: Verify default is `false`. Verify all 8 existing fields unchanged.

---

#### Task 2: Add `DebugLogging` key + read/write to SettingsStore.kt

**File**: `app/src/main/java/com/motherboard/focus/storage/SettingsStore.kt`

**Change 1** — Add key in `Keys` object (after line 53, before closing `}`):
```kotlin
val DebugLogging = booleanPreferencesKey("debug_logging")
```

**Change 2** — Add `debugLogging` read in `settings` Flow map (add after line 28, before the closing `)`) :
```kotlin
debugLogging = preferences[Keys.DebugLogging] ?: false,
```

**Change 3** — Add `debugLogging` write in `save()` method (add after line 41, before closing `}`):
```kotlin
preferences[Keys.DebugLogging] = settings.debugLogging
```

**QA**: Verify `Keys` object has 9 entries (8 old + 1 new). Verify `settings` Flow reads all 9 fields. Verify `save()` writes all 9 fields.

---

#### Task 3: Create `accessibility_service_config.xml`

**File**: `app/src/main/res/xml/accessibility_service_config.xml` (NEW)

**Content**:
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

**QA**: Verify file is valid XML. Verify no `typeViewScrolled` (Phase 3). Verify `canRetrieveWindowContent="false"`. Verify package filter is `com.google.android.youtube` only. Verify `settingsActivity` is fully qualified with package.

---

#### Task 4: Add accessibility strings to `strings.xml`

**File**: `app/src/main/res/values/strings.xml`

**Change**: Replace the entire file content with:

```xml
<resources>
    <string name="app_name">Motherboard</string>
    <string name="accessibility_service_description">Motherboard uses Android Accessibility permission to detect when YouTube Shorts is open, count Shorts scrolls, and show a cooldown overlay when your limit is reached.\n\nMotherboard does not read messages, store screen text, take screenshots, or send data anywhere.</string>
</resources>
```

**QA**: Verify `app_name` unchanged = "Motherboard". Verify description text matches MVP_SPEC.md lines 257-265. Verify `\n\n` for paragraph break.

---

### Wave 2: Service Class & Manifest (SEQUENTIAL within wave)

Task 5 creates the service class (depends on nothing). Task 6 depends on BOTH Task 3 (config XML must exist for `@xml/accessibility_service_config` reference) and Task 5 (service class must exist for `android:name` reference). Run Task 5 first, then Task 6 after Tasks 3 + 5 complete.

---

#### Task 5: Create `MotherboardAccessibilityService.kt`

**File**: `app/src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt` (NEW — create `service/` directory)

**Full content**:
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning.value = true
        if (debugLogging) {
            Log.d(TAG, "onServiceConnected: service bound")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!debugLogging) return

        val eventTypeName = event.eventTypeToString()
        Log.d(
            TAG,
            "event: type=$eventTypeName pkg=${event.packageName} cls=${event.className} time=${event.eventTime}"
        )
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

**Key design decisions**:
- `isRunning`: `MutableStateFlow<Boolean>` — hot; Compose observes with `collectAsState()`
- `debugLogging`: `@Volatile var` — set by DashboardViewModel from DataStore; read on every event
- `instance`: `@Volatile var` with `private set` — for future direct method calls (Phase 3-4)
- `eventTypeToString()`: extension function maps integer constants to readable names — avoids raw ints in logs
- **Privacy**: Logs event type, package name, class name, event time ONLY — NEVER logs `event.text`, `event.contentDescription`, or any window content
- No `rootInActiveWindow`, `windows`, or `findAccessibilityNodeInfosByText` calls (Phase 2 territory)

**QA**: Verify `TAG = "MotherboardA11y"`. Verify no `event.text` access. Verify no `rootInActiveWindow`. Verify all lifecycle overrides present. Verify `isRunning` transitions: `onServiceConnected` → true, `onDestroy` → false.

---

#### Task 6: Declare service in `AndroidManifest.xml`

**File**: `app/src/main/AndroidManifest.xml`

**Change**: Insert the `<service>` block inside `<application>`, AFTER the `</activity>` closing tag (after line 25) and BEFORE the `</application>` closing tag.

**Insert after line 25** (the `</activity>` closing tag):
```xml
        <service
            android:name=".service.MotherboardAccessibilityService"
            android:exported="true"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>
```

**Full manifest after change**:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.Motherboard">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@style/Theme.Motherboard"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <service
            android:name=".service.MotherboardAccessibilityService"
            android:exported="true"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>
    </application>

</manifest>
```

**QA**: Verify `BIND_ACCESSIBILITY_SERVICE` permission present. Verify `android:exported="true"`. Verify `<intent-filter>` with `android.accessibilityservice.AccessibilityService` action. Verify `<meta-data>` references `@xml/accessibility_service_config`. Verify NO `INTERNET` permission anywhere. Verify `android:name=".service.MotherboardAccessibilityService"` matches the actual fully-qualified class `com.motherboard.focus.service.MotherboardAccessibilityService`.

---

### Wave 3: UI & ViewModel Integration (SEQUENTIAL within wave)

These wire the new state into the dashboard. Order: ViewModel first (exposes state), then HomeScreen (consumes it), then App (passes it).

---

#### Task 7: Update `DashboardViewModel.kt` — add ContentObserver + setDebugLogging

**File**: `app/src/main/java/com/motherboard/focus/ui/DashboardViewModel.kt`

**Full replacement content** (all 41 lines replaced with expanded version):
```kotlin
package com.motherboard.focus.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motherboard.focus.service.MotherboardAccessibilityService
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

    companion object {
        private val Application.packageName: String
            get() = packageName
    }
}
```

**Key design decisions**:
- `_isAccessibilityServiceEnabled`: Private `MutableStateFlow` backed by `ContentObserver`. Initialized with current state on ViewModel creation.
- `contentObserver`: Registered in `init`, unregistered in `onCleared()`. Uses main thread `Handler` for `ContentObserver` callbacks.
- `debugLogging` sync: `viewModelScope.launch` collects `store.settings` and pushes `s.debugLogging` to `MotherboardAccessibilityService.debugLogging` (`@Volatile var`). This keeps the service companion in sync whenever the DataStore value changes.
- `isAccessibilityServiceEnabled()`: Private helper that queries `AccessibilityManager.getEnabledAccessibilityServiceList()` with `FEEDBACK_ALL_MASK`.
- `setDebugLogging()`: New public method that writes to DataStore via existing `update()` pattern.
- The `companion object` provides `Application.packageName` extension to avoid repeated `getApplication()` calls.

**QA**: Verify ContentObserver registered in `init`. Verify ContentObserver unregistered in `onCleared()`. Verify `_isAccessibilityServiceEnabled` initialized with current state. Verify `setDebugLogging` writes to DataStore. Verify `init` block launches `store.settings.collect` for debug sync.

---

#### Task 8: Update `HomeScreen.kt` — reactive accessibility status + debug toggle

**File**: `app/src/main/java/com/motherboard/focus/ui/screens/HomeScreen.kt`

**Changes**:

**Change 1** — Update the `HomeScreen` function signature to accept accessibility state:
```kotlin
@Composable
fun HomeScreen(
    settings: InterventionSettings,
    isServiceEnabled: Boolean,                          // NEW parameter
    onToggleBlocking: (Boolean) -> Unit,
    onSessionLimitChange: (Int) -> Unit,
    onCooldownMinutesChange: (Int) -> Unit,
    onToggleDebugLogging: (Boolean) -> Unit,            // NEW parameter
    modifier: Modifier = Modifier,
)
```

**Change 2** — Add Debug logging toggle in the Settings expandable section. Insert AFTER the `CooldownSlider(...)` call (after current line 102) and BEFORE the closing `}` of the `Column` (before current line 103):

```kotlin
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Debug logging", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (settings.debugLogging) "Logging accessibility events" else "No event logging",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.debugLogging,
                        onCheckedChange = onToggleDebugLogging,
                    )
                }
```

**Change 3** — Replace the hardcoded "Accessibility: Not enabled" text (current lines 109-113) with reactive content:

```kotlin
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Accessibility",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (isServiceEnabled) "Enabled" else "Not enabled",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isServiceEnabled)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error,
                    )
                }
```

**The Permissions section after changes (replacing current lines 107-120)**:
```kotlin
        ExpandableSection(title = "Permissions", initiallyExpanded = false) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Accessibility",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (isServiceEnabled) "Enabled" else "Not enabled",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isServiceEnabled)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error,
                    )
                }
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) {
                    Text("Open Accessibility Settings")
                }
            }
        }
```

**Note**: The `LocalContext.current` call at current line 106 must remain.

**QA**: Verify `isServiceEnabled` parameter is consumed (green "Enabled" vs red "Not enabled"). Verify debug logging toggle is in Settings section after Cooldown slider. Verify toggle writes through `onToggleDebugLogging`. Verify all existing sliders and expandable sections still work.

---

#### Task 9: Update `App.kt` — pass accessibility state to HomeScreen

**File**: `app/src/main/java/com/motherboard/focus/ui/App.kt`

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

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        HomeScreen(
            settings = settings,
            isServiceEnabled = isServiceEnabled,
            onToggleBlocking = viewModel::setBlockingEnabled,
            onSessionLimitChange = viewModel::setSessionLimit,
            onCooldownMinutesChange = viewModel::setCooldownMinutes,
            onToggleDebugLogging = viewModel::setDebugLogging,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
```

**QA**: Verify `isAccessibilityServiceEnabled` is collected with `collectAsState()`. Verify `isServiceEnabled` and `onToggleDebugLogging` are passed to `HomeScreen`. Verify build compiles (4-parameter → 6-parameter signature change is consistent).

---

### Wave 4: Test & Verification Gates

Contract tests and build verification.

---

#### Task 10: Update `PhaseZeroContractTest.kt` — manifest assertion update

**File**: `app/src/test/java/com/motherboard/focus/PhaseZeroContractTest.kt`

**Change**: Remove the assertion that NO AccessibilityService is declared. The existing test at lines 37-41 asserts absence of `AccessibilityService` in manifest. Phase 1 adds one — this test must be updated.

**Replace the test method at lines 37-41**:
```kotlin
    @Test
    fun `manifest now declares AccessibilityService for Phase 1`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("AccessibilityService must be declared in Phase 1", manifest.contains("MotherboardAccessibilityService"))
        assertTrue("BIND_ACCESSIBILITY_SERVICE permission required", manifest.contains("BIND_ACCESSIBILITY_SERVICE"))
        assertTrue("accessibility_service_config must be referenced", manifest.contains("accessibility_service_config"))
    }
```

**Also update the existing `manifest has no INTERNET permission` test to additionally verify the service is declared (manifest now SHOULD contain service)**:

**Replace the test method at current lines 31-34**:
```kotlin
    @Test
    fun `manifest has no INTERNET permission and service is declared`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("INTERNET permission must not be requested", manifest.contains("android.permission.INTERNET"))
        assertTrue("MotherboardAccessibilityService must be declared in Phase 1", manifest.contains("MotherboardAccessibilityService"))
    }
```

**QA**: All 8 existing tests still pass. New assertions verify manifest now contains service declaration.

---

#### Task 11: Create `PhaseOneContractTest.kt` — new contract tests

**File**: `app/src/test/java/com/motherboard/focus/PhaseOneContractTest.kt` (NEW)

**Full content**:
```kotlin
package com.motherboard.focus

import com.motherboard.focus.storage.InterventionSettings
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PhaseOneContractTest {

    @Test
    fun `InterventionSettings has 9 fields including debugLogging`() {
        val fieldNames = InterventionSettings::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("must contain debugLogging", "debugLogging" in fieldNames)
        assertTrue("must contain sessionLimit", "sessionLimit" in fieldNames)
        assertTrue("must contain cooldownDurationMillis", "cooldownDurationMillis" in fieldNames)
        assertTrue("must contain warningDurationMillis", "warningDurationMillis" in fieldNames)
        assertTrue("must contain eventDebounceMillis", "eventDebounceMillis" in fieldNames)
        assertTrue("must contain youtubeShortsEnabled", "youtubeShortsEnabled" in fieldNames)
        assertTrue("must contain blockingEnabled", "blockingEnabled" in fieldNames)
        assertTrue("must contain shortsBlockedToday", "shortsBlockedToday" in fieldNames)
        assertTrue("must contain cooldownsTriggeredToday", "cooldownsTriggeredToday" in fieldNames)
    }

    @Test
    fun `debugLogging defaults to false`() {
        val s = InterventionSettings()
        assertFalse("debugLogging must default to false (privacy-by-default)", s.debugLogging)
    }

    @Test
    fun `debugLogging copy works`() {
        val s = InterventionSettings().copy(debugLogging = true)
        assertTrue("debugLogging copy must work", s.debugLogging)
        assertFalse("original must remain unchanged", InterventionSettings().debugLogging)
    }

    @Test
    fun `all Phase 0 fields still present and default correctly`() {
        val s = InterventionSettings()
        assertEquals(10, s.sessionLimit)
        assertEquals(5 * 60 * 1000L, s.cooldownDurationMillis)
        assertEquals(2000L, s.warningDurationMillis)
        assertEquals(1000L, s.eventDebounceMillis)
        assertTrue(s.youtubeShortsEnabled)
        assertTrue(s.blockingEnabled)
        assertEquals(0, s.shortsBlockedToday)
        assertEquals(0, s.cooldownsTriggeredToday)
    }

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

    @Test
    fun `service class exists in correct package`() {
        val serviceFile = File("src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt")
        assertTrue("MotherboardAccessibilityService.kt must exist", serviceFile.exists())
        val content = serviceFile.readText()
        assertTrue("must extend AccessibilityService", content.contains("AccessibilityService()"))
        assertTrue("must use tag MotherboardA11y", content.contains("MotherboardA11y"))
        assertTrue("must override onAccessibilityEvent", content.contains("override fun onAccessibilityEvent"))
        assertTrue("must override onServiceConnected", content.contains("override fun onServiceConnected"))
        assertTrue("must override onInterrupt", content.contains("override fun onInterrupt"))
        assertTrue("must override onDestroy", content.contains("override fun onDestroy"))
        assertTrue("must have debugLogging companion var", content.contains("@Volatile") && content.contains("debugLogging"))
        assertTrue("must have isRunning StateFlow", content.contains("isRunning"))
    }

    @Test
    fun `no INTERNET permission in manifest`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("INTERNET must not be declared", manifest.contains("android.permission.INTERNET"))
    }

    @Test
    fun `strings xml has accessibility description`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue("must have accessibility_service_description", strings.contains("accessibility_service_description"))
        assertTrue("must explain why permission is needed", strings.contains("YouTube Shorts"))
        assertTrue("must state no data is sent", strings.contains("does not read messages"))
    }
}
```

**QA**: All tests pass with `./gradlew testDebugUnitTest`. Verify no Mockito/MockK needed (pure assertions only).

---

#### Task 12: Run `./gradlew assembleDebug`

**Command**:
```bash
./gradlew assembleDebug
```

**Expected**: BUILD SUCCESSFUL. Zero compilation errors.

**If fails**: Check for signature mismatches between HomeScreen.kt and App.kt (6-parameter call). Check for missing imports in DashboardViewModel.kt.

---

#### Task 13: Run `./gradlew testDebugUnitTest`

**Command**:
```bash
./gradlew testDebugUnitTest
```

**Expected**: All tests pass. Existing PhaseZeroContractTest tests + 8 new PhaseOneContractTest tests all passing.

---

#### Task 14: Manual testing

Follow the Manual Test Checklist from the Final Verification Wave section. Install on test phone, enable service, verify logcat, verify dashboard reactivity, toggle debug logging, verify persistence.

---

## Final Verification Wave

| Gate | Command | Expected |
|---|---|---|
| **Build** | `./gradlew assembleDebug` | BUILD SUCCESSFUL |
| **Unit tests** | `./gradlew testDebugUnitTest` | All pass |
| **No INTERNET** | `grep -r "INTERNET" app/src/main/AndroidManifest.xml` | Zero matches |
| **No raw text logging** | `grep -r "event.text" app/src/main/` and `grep -r "contentDescription" app/src/main/` | Zero matches in service class |
| **Service in manifest** | `grep "MotherboardAccessibilityService" app/src/main/AndroidManifest.xml` | Present |

### Manual Test Checklist

| # | Test | Expected |
|---|---|---|
| 1 | Install app | App opens, dashboard visible |
| 2 | Toggle Debug logging ON in dashboard Settings | Switch flips; persists after app restart |
| 3 | Enable Motherboard in Android Accessibility settings | Service should bind |
| 4 | Open YouTube app | logcat shows `MotherboardA11y` events with `pkg=com.google.android.youtube` |
| 5 | Open any non-YouTube app | No `MotherboardA11y` events (package filter) |
| 6 | Dashboard Permissions section | Shows "Accessibility: Enabled" (green text) |
| 7 | Disable Motherboard in Accessibility settings | Dashboard shows "Accessibility: Not enabled" (error color) |
| 8 | Toggle Debug logging OFF | logcat stops showing events; persists after app restart |
| 9 | App does not crash during any of the above | No force-close |
| 10 | YouTube not installed on device (if test device allows) | App opens normally; Permissions section shows "Not enabled"; no crash |

### Stop Condition

**All verification gates pass + manual tests 1-10 pass.** Stop before Phase 2.

### Test Coverage Notes

The Phase 1 contract tests (PhaseOneContractTest.kt, 8 tests) cover file existence and structure: InterventionSettings field count and defaults, config XML content, service class structure, manifest constraints, and strings. These are **structure-level** contract tests — they verify the codebase artifacts match the spec.

**Explicitly deferred to manual testing** (not in unit tests):
- SettingsStore read/write behavior for `debugLogging` — verified via manual test (toggle persists after restart, test #2/#8)
- DashboardViewModel ContentObserver reactivity — verified via manual test (real-time status in dashboard, test #6/#7)
- HomeScreen signature and App.kt pass-through — verified via build gate (compilation succeeds)
- Actual AccessibilityService event delivery — verified via manual test (logcat output, test #4/#5)
- Service-start-before-app edge case — accepted for Phase 1 (guardrail documented above)
