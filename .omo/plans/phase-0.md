# Motherboard Phase 0: App Shell & Dashboard

## Plan Metadata

| Field | Value |
|---|---|
| **Plan ID** | `phase-0` |
| **Created** | 2026-06-09 |
| **Status** | Ready for execution |
| **App** | Motherboard (YouTube Shorts blocker) |
| **Phase scope** | App shell + dashboard UI + DataStore persistence |
| **Phase explicitly OUT** | AccessibilityService, Shorts detection, counting, overlays, blocking, INTERNET, AI, analytics |
| **Package** | `com.motherboard.focus` (unchanged) |
| **Tech stack** | Kotlin, Jetpack Compose, Material3, DataStore Preferences, ViewModel |

## Architecture Decisions (Frozen)

| Decision | Choice | Rationale |
|---|---|---|
| State owner | `DashboardViewModel` | Canonical Android pattern; owns `SettingsStore`, exposes `StateFlow<InterventionSettings>` |
| Coroutine scope | `viewModelScope` | Tied to ViewModel lifecycle, auto-cancelled |
| Settings write timing | `onValueChangeFinished` for sliders; immediate for toggle | Prevents DataStore write storms during slider drag |
| DataStore race tolerance | Acceptable for Phase 0 | Single-threaded UI; no concurrent writers. Add `Mutex` in Phase 5 polish if needed. |
| Theme | Dark-only, big typography | Enforced in `MotherboardTheme` (always uses `darkColorScheme`) |
| Dashboard structure | Single scrollable screen, expandable sections | `Column` with `verticalScroll(rememberScrollState())` |
| Permission button | Opens `Settings.ACTION_ACCESSIBILITY_SETTINGS` | Establishes UX flow; service comes in Phase 1 |
| Stats in Phase 0 | Display `0` with static labels | Counting not implemented yet; real values in Phase 3-4 |
| Test strategy | `./gradlew testDebugUnitTest` + `./gradlew assembleDebug` as gates | Command-level executable; manual QA supplementary per phased plan |

## Dashboard Contract (Frozen)

### Always Visible

```
┌──────────────────────────────────────┐
│  Motherboard                          │  ← MaterialTheme.typography.headlineLarge
│  Shorts blocker for your attention... │  ← MaterialTheme.typography.bodyLarge, muted
├──────────────────────────────────────┤
│  YouTube Shorts Blocking        [ON]  │  ← Card with Switch (Material3)
├──────────────────────────────────────┤
│         0 / 10                        │  ← MaterialTheme.typography.displayLarge (big number)
│    Shorts this session               │  ← MaterialTheme.typography.bodyMedium, muted
└──────────────────────────────────────┘
```

### Expandable Section 1: Stats (collapsed by default)

```
▶ Stats                    ── collapsed state
▼ Stats                    ── expanded state
  Shorts blocked today: 0
  Cooldowns triggered today: 0
```

### Expandable Section 2: Settings (expanded by default)

```
▼ Settings                 ── expanded by default
  Hard limit: 10 Shorts    ── Slider (3..50, step 1)
  Cooldown: 5 minutes      ── Slider (1..30, step 1)
```

### Expandable Section 3: Permissions (collapsed by default)

```
▶ Permissions              ── collapsed state
▼ Permissions              ── expanded state
  Accessibility: Not enabled
  [Open Accessibility Settings]  ── Button → ACTION_ACCESSIBILITY_SETTINGS
```

## Data Model (Post-Prune)

```kotlin
data class InterventionSettings(
    val sessionLimit: Int = 10,                    // 3..50
    val cooldownDurationMillis: Long = 300_000L,   // 60_000..1_800_000 (1..30 min)
    val warningDurationMillis: Long = 2000L,       // fixed for now
    val eventDebounceMillis: Long = 1000L,         // fixed for now
    val youtubeShortsEnabled: Boolean = true,      // YouTube Shorts inclusion flag
    val blockingEnabled: Boolean = true,           // master blocking switch (the toggle)
    val shortsBlockedToday: Int = 0,               // stat: read-only in Phase 0
    val cooldownsTriggeredToday: Int = 0,          // stat: read-only in Phase 0
)
```

---

## Tasks

### Wave 1: Independent File-Level Changes (ALL PARALLEL)

**Compile verification for Wave 1**: Most tasks in this wave cannot compile in isolation (cross-references between InterventionSettings and SettingsStore; deleted screens still imported). Individual per-task compile gates are deferred. **Full compilation is verified in Wave 2 after Task 11**, then gated at Task 14. Tasks 6 (Theme) and 7 (dependency) can be verified individually.

---

#### Task 1: Prune InterventionSettings.kt

**File**: `app/src/main/java/com/motherboard/focus/storage/InterventionSettings.kt`

**What**: Remove 3 out-of-scope fields, add 3 missing fields.

**Changes**:
1. **REMOVE** these fields and their defaults:
   - `instagramReelsEnabled: Boolean = true`
   - `inactiveSurfaceTimeoutSeconds: Int = 30`
   - `reducedMotion: Boolean = false`
2. **ADD** these fields:
   - `blockingEnabled: Boolean = true`
   - `shortsBlockedToday: Int = 0`
   - `cooldownsTriggeredToday: Int = 0`

**Final data class**:
```kotlin
package com.motherboard.focus.storage

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

**QA Gate** (deferred): Cannot compile in isolation — `SettingsStore.kt` still references fields removed in Task 1. Compile verified in Wave 2 after Task 11 completes.

---

#### Task 2: Prune SettingsStore.kt

**File**: `app/src/main/java/com/motherboard/focus/storage/SettingsStore.kt`

**What**: Remove keys for pruned fields, add keys for new fields. Update Flow mapping and save().

**Changes**:
1. **REMOVE** from `Keys` object: `InstagramReelsEnabled`, `InactiveSurfaceTimeoutSeconds`, `ReducedMotion`
2. **ADD** to `Keys` object:
   - `val BlockingEnabled = booleanPreferencesKey("blocking_enabled")`
   - `val ShortsBlockedToday = intPreferencesKey("shorts_blocked_today")`
   - `val CooldownsTriggeredToday = intPreferencesKey("cooldowns_triggered_today")`
3. **UPDATE** `settings` Flow mapping: remove pruned fields, add new fields with defaults
4. **UPDATE** `save()`: remove writes for pruned keys, add writes for new keys

**QA Gate** (deferred): Cannot compile in isolation — `InterventionSettings.kt` may have been modified first, changing the field set. Compile verified in Wave 2 after Task 11.

---

#### Task 3: Delete SettingsScreen.kt

**File**: `app/src/main/java/com/motherboard/focus/ui/screens/SettingsScreen.kt`

**Action**: Delete the entire file.

**QA Gate**:
- Command: `test ! -f app/src/main/java/com/motherboard/focus/ui/screens/SettingsScreen.kt && echo "PASS: file deleted" || echo "FAIL: file still exists"`
- Expected: `PASS: file deleted`
- Integration compile: Verified in Task 14

---

#### Task 4: Delete StatsScreen.kt

**File**: `app/src/main/java/com/motherboard/focus/ui/screens/StatsScreen.kt`

**Action**: Delete the entire file.

**QA Gate**:
- Command: `test ! -f app/src/main/java/com/motherboard/focus/ui/screens/StatsScreen.kt && echo "PASS: file deleted" || echo "FAIL: file still exists"`
- Expected: `PASS: file deleted`

---

#### Task 5: Delete PermissionsScreen.kt

**File**: `app/src/main/java/com/motherboard/focus/ui/screens/PermissionsScreen.kt`

**Action**: Delete the entire file.

**QA Gate**:
- Command: `test ! -f app/src/main/java/com/motherboard/focus/ui/screens/PermissionsScreen.kt && echo "PASS: file deleted" || echo "FAIL: file still exists"`
- Expected: `PASS: file deleted`

---

#### Task 6: Update Theme.kt — Enforce Dark-Only

**File**: `app/src/main/java/com/motherboard/focus/ui/theme/Theme.kt`

**What**: Simplify to always-dark. Remove dynamic color, light theme, and all parameters except `content`.

**Full replacement**:
```kotlin
package com.motherboard.focus.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val MotherboardColorScheme = darkColorScheme(
    primary = Evergreen80,
    secondary = Sand80,
    tertiary = Ember80,
)

@Composable
fun MotherboardTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = MotherboardColorScheme,
        typography = Typography,
        content = content,
    )
}
```

**QA Gate**:
- Command: `./gradlew compileDebugKotlin`
- Expected: `BUILD SUCCESSFUL`

---

#### Task 7: Add lifecycle-viewmodel-compose Dependency

**Files**: `gradle/libs.versions.toml` AND `app/build.gradle.kts`

**What**: `DashboardViewModel` (Task 9) uses `AndroidViewModel` which requires `lifecycle-viewmodel-compose` for the `viewModel()` Compose delegate. This is NOT a transitive dependency of `lifecycle-runtime-ktx` in all versions — add it explicitly.

**Step A — `gradle/libs.versions.toml`**:
Add to `[versions]`:
```toml
lifecycleViewmodelCompose = "2.8.7"
```
Add to `[libraries]`:
```toml
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycleViewmodelCompose" }
```

**Step B — `app/build.gradle.kts`**:
Add to `dependencies` block:
```kotlin
implementation(libs.androidx.lifecycle.viewmodel.compose)
```

**QA Gate**:
- Command: `./gradlew dependencies --configuration debugRuntimeClasspath | grep lifecycle-viewmodel-compose`
- Expected: Shows the dependency is resolved (non-empty output)

---

#### Task 8: Clean Up colors.xml

**File**: `app/src/main/res/values/colors.xml`

**Action**: Replace with:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Compose theme colors are defined in Color.kt -->
</resources>
```

**QA Gate**: Trivial — no compile impact.

---

### Wave 2: ViewModel + UI Rewrite + Wiring (SEQUENTIAL — Task 9 → Task 10 → Task 11 → Task 12)

**All four tasks must compile together.** Run `./gradlew compileDebugKotlin` after Task 12.

---

#### Task 9: Create DashboardViewModel.kt

**File**: CREATE `app/src/main/java/com/motherboard/focus/ui/DashboardViewModel.kt`

**What**: ViewModel that owns `SettingsStore`, exposes `StateFlow<InterventionSettings>`, and provides update callbacks with `coerceIn` validation.

```kotlin
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
```

**Design notes**:
- `AndroidViewModel` because `SettingsStore` needs `Application` context
- `WhileSubscribed(5000)` keeps flow alive through config changes
- `coerceIn` validates at ViewModel boundary
- Single `update()` helper — DRY
- **DataStore lost-update risk**: If two `update()` calls interleave (e.g., rapid toggle + slider change), the second `settings.value` snapshot may be stale. This is **acceptable for Phase 0** because the UI is single-threaded and does not issue concurrent writes. Add `Mutex` in Phase 5 if needed.

**QA Gate** (deferred): Cannot compile in isolation — deleted screen files (Tasks 3-5) are still imported by `App.kt` until Task 11 rewrites it. Compile verified in Wave 2 after Task 11.

---

#### Task 10: Rewrite HomeScreen.kt — Expandable Scrollable Dashboard

**File**: `app/src/main/java/com/motherboard/focus/ui/screens/HomeScreen.kt`

**What**: Complete rewrite. Single scrollable screen with dark-themed expandable cards. **This is the largest task (~280 lines).**

**Key decisions realized in code**:
- `Column` wrapped in `verticalScroll(rememberScrollState())` — scrollable on small devices
- Sliders use `mutableFloatStateOf` + `LaunchedEffect` pattern — real-time visual feedback, write only on `onValueChangeFinished`
- Expandable sections via `AnimatedVisibility` with `expandVertically`/`shrinkVertically`
- Permission button opens `Settings.ACTION_ACCESSIBILITY_SETTINGS`
- All cards use `surfaceVariant.copy(alpha = 0.5f)` for dark card styling

```kotlin
package com.motherboard.focus.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.motherboard.focus.storage.InterventionSettings

@Composable
fun HomeScreen(
    settings: InterventionSettings,
    onToggleBlocking: (Boolean) -> Unit,
    onSessionLimitChange: (Int) -> Unit,
    onCooldownMinutesChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Title ──
        Text(
            text = "Motherboard",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Shorts blocker for your attention span",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── Status card (always visible) ──
        DashboardCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("YouTube Shorts Blocking", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (settings.blockingEnabled) "Active" else "Paused",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.blockingEnabled,
                    onCheckedChange = onToggleBlocking,
                )
            }
        }

        // ── Session count card (always visible) ──
        DashboardCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "0 / ${settings.sessionLimit}",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Shorts this session",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Stats (expandable, collapsed by default) ──
        ExpandableSection(title = "Stats", initiallyExpanded = false) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatRow(label = "Shorts blocked today", value = "${settings.shortsBlockedToday}")
                StatRow(label = "Cooldowns triggered today", value = "${settings.cooldownsTriggeredToday}")
            }
        }

        // ── Settings (expandable, expanded by default) ──
        ExpandableSection(title = "Settings", initiallyExpanded = true) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HardLimitSlider(
                    value = settings.sessionLimit,
                    onValueChangeFinished = onSessionLimitChange,
                )
                CooldownSlider(
                    cooldownMillis = settings.cooldownDurationMillis,
                    onValueChangeFinished = onCooldownMinutesChange,
                )
            }
        }

        // ── Permissions (expandable, collapsed by default) ──
        val context = LocalContext.current
        ExpandableSection(title = "Permissions", initiallyExpanded = false) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Accessibility: Not enabled",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) {
                    Text("Open Accessibility Settings")
                }
            }
        }

        // Bottom spacer so content isn't clipped by system bars
        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ── Reusable composables ──

@Composable
private fun DashboardCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(20.dp), content = content)
    }
}

@Composable
private fun ExpandableSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (expanded) "▼" else "▲",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

// ── Slider components with debounced writes ──

@Composable
private fun HardLimitSlider(
    value: Int,
    onValueChangeFinished: (Int) -> Unit,
) {
    var sliderValue by remember { mutableFloatStateOf(value.toFloat()) }

    // Sync with external settings changes
    LaunchedEffect(value) {
        sliderValue = value.toFloat()
    }

    Column {
        Text(
            "Hard limit: ${sliderValue.toInt()} Shorts",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onValueChangeFinished(sliderValue.toInt()) },
            valueRange = 3f..50f,
            steps = 46, // (50-3-1) = 46 discrete steps → integer values
        )
    }
}

@Composable
private fun CooldownSlider(
    cooldownMillis: Long,
    onValueChangeFinished: (Int) -> Unit,
) {
    val cooldownMins = (cooldownMillis / 60_000L).toInt()
    var sliderValue by remember { mutableFloatStateOf(cooldownMins.toFloat()) }

    LaunchedEffect(cooldownMins) {
        sliderValue = cooldownMins.toFloat()
    }

    Column {
        Text(
            "Cooldown: ${sliderValue.toInt()} minutes",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onValueChangeFinished(sliderValue.toInt()) },
            valueRange = 1f..30f,
            steps = 28, // (30-1-1) = 28 discrete steps → integer values
        )
    }
}
```

**QA Gate**:
- Command: `./gradlew compileDebugKotlin`
- Expected: `BUILD SUCCESSFUL`
- Note: Compile may fail if Task 11 (App.kt) has not yet been updated — run both tasks together.

---

#### Task 11: Rewrite App.kt — Single Screen, No Navigation

**File**: `app/src/main/java/com/motherboard/focus/ui/App.kt`

**What**: Remove entire 4-tab `NavigationBar`, `AppSection` enum, and screen-switching `when` block. Replace with single-screen dashboard calling the new `HomeScreen` signature.

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

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        HomeScreen(
            settings = settings,
            onToggleBlocking = viewModel::setBlockingEnabled,
            onSessionLimitChange = viewModel::setSessionLimit,
            onCooldownMinutesChange = viewModel::setCooldownMinutes,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
```

**Removed**:
- `AppSection` enum (lines 21-29 in current)
- `var selectedSection` state + `NavigationBar` (lines 35-49 in current)
- `when (selectedSection)` block (lines 53-58 in current)
- Imports for `SettingsScreen`, `StatsScreen`, `PermissionsScreen`

**QA Gate**:
- Command: `./gradlew compileDebugKotlin`
- Expected: `BUILD SUCCESSFUL` (must pass if Tasks 9-11 are complete)

---

#### Task 12: Update MainActivity.kt — Wire ViewModel, Dark Theme

**File**: `app/src/main/java/com/motherboard/focus/MainActivity.kt`

**What**: Replace static `InterventionSettings()` default with real `DashboardViewModel`. Call simplified `MotherboardTheme()` (no parameters — Task 6 already removed `darkTheme` param).

```kotlin
package com.motherboard.focus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.motherboard.focus.ui.DashboardViewModel
import com.motherboard.focus.ui.MotherboardApp
import com.motherboard.focus.ui.theme.MotherboardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MotherboardTheme {
                val viewModel: DashboardViewModel = viewModel()
                MotherboardApp(viewModel = viewModel)
            }
        }
    }
}
```

**QA Gate**:
- Command: `./gradlew compileDebugKotlin`
- Expected: `BUILD SUCCESSFUL`
- After this task, the FULL project should compile. This is the first real compile gate.

---

### Wave 3: Tests (depends on Wave 2)

**File**: `app/src/test/java/com/motherboard/focus/PhaseZeroContractTest.kt`

**What**: Full replacement. Old tests assert `AppSection` and `instagramReelsEnabled` — both invalid. New tests cover pruned data model, manifest constraints, and pruning verification. **No Kotlin reflection** — use explicit field-name checks to avoid `kotlin-reflect` dependency.

```kotlin
package com.motherboard.focus

import com.motherboard.focus.storage.InterventionSettings
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PhaseZeroContractTest {

    // ── Data model defaults ──

    @Test
    fun `InterventionSettings defaults match MVP spec`() {
        val s = InterventionSettings()

        assertEquals("sessionLimit default", 10, s.sessionLimit)
        assertEquals("cooldownDurationMillis default", 5 * 60 * 1000L, s.cooldownDurationMillis)
        assertEquals("warningDurationMillis default", 2000L, s.warningDurationMillis)
        assertEquals("eventDebounceMillis default", 1000L, s.eventDebounceMillis)
        assertTrue("youtubeShortsEnabled default", s.youtubeShortsEnabled)
        assertTrue("blockingEnabled default", s.blockingEnabled)
        assertEquals("shortsBlockedToday default", 0, s.shortsBlockedToday)
        assertEquals("cooldownsTriggeredToday default", 0, s.cooldownsTriggeredToday)
    }

    @Test
    fun `sessionLimit copy works`() {
        val s = InterventionSettings().copy(sessionLimit = 25)
        assertEquals(25, s.sessionLimit)
        assertEquals(10, InterventionSettings().sessionLimit) // original unchanged
    }

    // ── Manifest constraints ──

    @Test
    fun `manifest has no INTERNET permission`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(
            "INTERNET permission must not be requested",
            manifest.contains("android.permission.INTERNET"),
        )
    }

    @Test
    fun `manifest has no AccessibilityService declaration`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(
            "AccessibilityService must not be declared in Phase 0",
            manifest.contains("AccessibilityService"),
        )
        assertFalse(
            "isAccessibilityTool must not be declared",
            manifest.contains("isAccessibilityTool"),
        )
    }

    // ── Pruning verification ──

    @Test
    fun `no ReelGuard or Instagram references in InterventionSettings`() {
        val fields = InterventionSettings::class.java.declaredFields.map { it.name }
        val allText = fields.joinToString(" ").lowercase()

        assertFalse("ReelGuard must not appear in field names", allText.contains("reelguard"))
        assertFalse("Instagram must not appear in field names", allText.contains("instagram"))
        assertFalse("Reels must not appear in field names", allText.contains("reels"))
    }

    @Test
    fun `pruned fields are absent`() {
        val fieldNames = InterventionSettings::class.java.declaredFields.map { it.name }

        assertFalse("inactiveSurfaceTimeoutSeconds must be removed", "inactiveSurfaceTimeoutSeconds" in fieldNames)
        assertFalse("reducedMotion must be removed", "reducedMotion" in fieldNames)
        assertFalse("instagramReelsEnabled must be removed", "instagramReelsEnabled" in fieldNames)
    }

    @Test
    fun `new fields are present`() {
        val fieldNames = InterventionSettings::class.java.declaredFields.map { it.name }

        assertTrue("blockingEnabled must be present", "blockingEnabled" in fieldNames)
        assertTrue("shortsBlockedToday must be present", "shortsBlockedToday" in fieldNames)
        assertTrue("cooldownsTriggeredToday must be present", "cooldownsTriggeredToday" in fieldNames)
    }

    @Test
    fun `youtubeShortsEnabled is still present`() {
        val fieldNames = InterventionSettings::class.java.declaredFields.map { it.name }
        assertTrue("youtubeShortsEnabled must remain", "youtubeShortsEnabled" in fieldNames)
    }
}
```

**QA Gate**:
- Command: `./gradlew testDebugUnitTest`
- Expected: `BUILD SUCCESSFUL` — 8/8 tests pass, 0 failures, 0 skipped
- Evidence: `app/build/reports/tests/testDebugUnitTest/index.html`

---

### Wave 4: Integration Verification (SEQUENTIAL — gates must pass in order)

---

#### Task 14: Full Build Verification

**Command**: `./gradlew assembleDebug`

**Expected**: `BUILD SUCCESSFUL`
- APK generated at `app/build/outputs/apk/debug/app-debug.apk`

**If failure**: Fix compilation errors before proceeding. Common causes:
- Forgotten import cleanup in remaining files
- `AppSection` references not fully removed
- Missing `lifecycle-viewmodel-compose` dependency

---

#### Task 15: Unit Test Gate

**Command**: `./gradlew testDebugUnitTest`

**Expected**: `BUILD SUCCESSFUL` — 8/8 tests pass, 0 failures, 0 skipped

---

#### Task 16: Cruft Grep — Brand Names

**Command**: `grep -rni "reelguard\|instagram\|reels" app/src/main/`

**Expected**: 0 matches. Exit code 1 (no matches found).

**If any match**: Find and remove, then rerun.

---

#### Task 17: Cruft Grep — Removed Fields

**Command**: `grep -rn "inactiveSurfaceTimeoutSeconds\|reducedMotion\|instagramReelsEnabled" app/src/main/`

**Expected**: 0 matches in source. Exit code 1.
**Note**: Scoped to `app/src/main/` only — test files in `app/src/test/` intentionally reference removed field names in assertions.

---

#### Task 18: Manifest Safety Check

**Command**: `grep -c "INTERNET\|AccessibilityService\|isAccessibilityTool" app/src/main/AndroidManifest.xml || true`

**Expected**: `0`

---

#### Task 19: Delete ExampleInstrumentedTest.kt (Optional)

**File**: `app/src/androidTest/java/com/motherboard/focus/ExampleInstrumentedTest.kt`

**Action**: Delete (template test, only checks package name).

---

## Dependency Graph

```
Wave 1: ALL PARALLEL (independent file edits; compile verified in Wave 2 after Task 12)
  Task 1 ─── Task 2 ─── Task 3 ─── Task 4 ─── Task 5 ─── Task 6 ─── Task 7 ─── Task 8
                                    │
                                    ▼
Wave 2: SEQUENTIAL (each depends on previous + all of Wave 1)
  Task 9 ──→ Task 10 ──→ Task 11 ──→ Task 12
  (ViewModel)  (HomeScreen)  (App.kt)   (MainActivity)
  ┌─ First compile gate: ./gradlew compileDebugKotlin ─┐
                                    │
                                    ▼
Wave 3: SEQUENTIAL (depends on ALL of Wave 2)
  Task 13
  (Contract Tests)
  ┌─ Test gate: ./gradlew testDebugUnitTest ─┐
                                    │
                                    ▼
Wave 4: SEQUENTIAL (depends on ALL of Wave 3)
  Task 14 → Task 15 → Task 16 → Task 17 → Task 18 → Task 19
  (Build)   (Tests)   (Grep)    (Grep)    (Manifest) (Cleanup)
```

**Compile Note**: Tasks 1-5, 9 may cause intermediate compile failures individually. Full compilation is first verified at the end of Wave 2 (after Task 12). This is intentional — the Wave 2 tasks are designed as a single atomic UI rewrite batch.

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| `lifecycle-viewmodel-compose` missing from deps | Medium | High (compile fail at Task 12) | Task 7 adds it explicitly |
| Theme signature mismatch between Task 6 and Task 12 | LOW (fixed) | High | Task 6 removes `darkTheme` param; Task 12 calls `MotherboardTheme()` without params |
| DataStore lost-update from concurrent ViewModel writes | Low | Low | Single-threaded UI, no concurrent writers. Documented as acceptable. |
| Kotlin reflection test failure | LOW (removed) | Medium | Task 13 uses `java.declaredFields`, no `kotlin-reflect` needed |
| Dashboard clips on small screens | Medium | Medium | Task 10 uses `verticalScroll(rememberScrollState())` |
| Slider debounce: `LaunchedEffect` key flicker on rapid external changes | Low | Low | Settings only change through sliders themselves, no external writers |
| Gradle cache issues after mass deletion | Low | Medium | `./gradlew clean` before Task 14 if needed |
| `displayLarge` typography not available in older Material3 | Low | Low | Available in Compose BOM 2026.02.01 |

## Final Verification Wave

> ⚠️ **STOP CONDITION**: After ALL tasks above pass their gates, the implementer MUST:
> 1. Present a summary of every file changed (list paths)
> 2. Present build result: `./gradlew assembleDebug`
> 3. Present test result: `./gradlew testDebugUnitTest` (8/8 pass)
> 4. Present cruft grep results (0 matches for ReelGuard/Instagram/Reels)
> 5. **Ask user for explicit "okay" before marking work complete**

### Manual Test Checklist (supplementary, from spec)

After build + test + grep pass, the user should manually verify:

| # | Check | Expected |
|---|---|---|
| 1 | App opens | Dashboard visible, dark theme |
| 2 | Toggle persists after restart | `blockingEnabled` saved to DataStore |
| 3 | Hard limit slider persists after restart | `sessionLimit` saved to DataStore |
| 4 | Cooldown slider persists after restart | `cooldownDurationMillis` saved to DataStore |
| 5 | Accessibility Settings button opens system settings | `ACTION_ACCESSIBILITY_SETTINGS` intent fires |
| 6 | No "ReelGuard" text anywhere | Clean Motherboard branding |
| 7 | No "Instagram" or "Reels" text anywhere | Clean scope |
| 8 | Expandable sections work | Stats/Permissions collapse/expand with animation |
| 9 | Screen scrolls on small device | `verticalScroll` working |
| 10 | Slider drag is smooth | `mutableFloatStateOf` provides real-time feedback |

## TODO After Phase 0 Completes

- [ ] User confirms manual test checklist
- [ ] Proceed to Phase 1: AccessibilityService skeleton (see `docs/PHASED_BUILD_AND_TEST_PLAN.md`)
