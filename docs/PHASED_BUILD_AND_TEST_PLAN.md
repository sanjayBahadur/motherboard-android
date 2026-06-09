# Motherboard Phased Build and Test Plan

Build the app in small phases.

Each phase must:

1. compile
2. install on the test phone if possible
3. be manually tested
4. stop before adding the next phase

Do not build the whole app in one giant agent run. That is how projects become haunted.

## Phase 0: App shell and dashboard

### Goal

Create the basic Android app with the main UI and settings storage.

### Build

Implement:

- Kotlin Android project
- Jetpack Compose
- app name: Motherboard
- dashboard screen
- settings state model
- DataStore Preferences
- navigation-free single-screen UI if possible
- Accessibility permission status display
- button to open Android Accessibility settings

Do not implement AccessibilityService yet.

### Dashboard UI

Show:

- Motherboard title
- ON/OFF toggle for YouTube Shorts blocking
- session count card: `0 / limit`
- blocked today card
- cooldowns today card
- hard limit setting
- cooldown minutes setting
- permission card

### Test

Run:

```bash
./gradlew assembleDebug
```

Manual test:

- app opens
- UI does not crash
- toggle persists after app restart
- hard limit persists after app restart
- cooldown setting persists after app restart
- Accessibility settings button opens Android settings

### Stop condition

Stop after Phase 0 passes.

## Phase 1: AccessibilityService skeleton

### Goal

Add the accessibility service without counting or blocking yet.

### Build

Implement:

- `MotherboardAccessibilityService`
- service declaration in manifest
- accessibility service config XML
- package filter for YouTube if supported
- basic event logging behind a debug flag
- service enabled/disabled state reflected in dashboard

The service should observe YouTube-related events only when possible.

Do not add overlays yet.

### Test

Run:

```bash
./gradlew assembleDebug
```

Manual test:

- install app
- enable Motherboard in Android Accessibility settings
- open YouTube
- open some non-YouTube app
- verify the app does not crash
- verify logcat shows YouTube events only or mostly YouTube events
- disable the service and verify app handles it cleanly

Suggested log tag:

```text
MotherboardA11y
```

### Stop condition

Stop after the service runs without crashing.

## Phase 2: YouTube Shorts detection

### Goal

Detect when the user is probably inside YouTube Shorts.

### Build

Implement:

- `YouTubeShortsDetector`
- small detection state:
  - `NotYouTube`
  - `YouTubeNotShorts`
  - `YouTubeShorts`
- debug logging for why Shorts was detected
- dashboard indicator:
  - `Watching YouTube Shorts: yes/no`

Use simple accessibility node markers. Keep it easy to update.

Do not count yet.

### Test

Run:

```bash
./gradlew assembleDebug
```

Manual test:

- open YouTube home
- open normal YouTube video
- open search
- open subscriptions
- open YouTube Shorts
- verify Shorts detection is only true on Shorts
- rotate phone if supported
- close YouTube
- reopen YouTube

### Stop condition

Stop after Shorts detection is good enough on the test phone.

Good enough means:

```text
Shorts screen: detected
Normal YouTube video: not detected
YouTube home/search/subscriptions: not detected
```

## Phase 3: Count Shorts scrolls and show warnings

### Goal

Count scroll events while Shorts is active.

### Build

Implement:

- session count
- debounced increment on `TYPE_VIEW_SCROLLED`
- DataStore-backed current session state
- 2-second warning overlay after increment
- warning color level based on count/limit

Do not block yet.

### Counting rule

```text
If blocking enabled
AND service enabled
AND package is com.google.android.youtube
AND screen state is YouTubeShorts
AND event type is TYPE_VIEW_SCROLLED
AND now - lastCountedAt >= debounceMs
THEN sessionCount += 1
```

### Test

Run:

```bash
./gradlew assembleDebug
```

Manual test:

- open Shorts
- scroll once
- count increases by 1
- warning appears for around 2 seconds
- warning disappears
- rapid tiny movement should not count repeatedly
- normal YouTube video scrolling should not count
- YouTube home scrolling should not count
- app dashboard shows updated count

### Stop condition

Stop after the count is stable enough.

## Phase 4: Cooldown blocking overlay

### Goal

Block Shorts when the hard limit is reached.

### Build

Implement:

- blocking overlay
- countdown timer
- session reset after cooldown
- increment `cooldowns_triggered_today`
- increment `shorts_blocked_today` when cooldown starts
- keep system navigation usable
- remove overlay after timer ends

### Test

Set hard limit to 3 for testing.

Run:

```bash
./gradlew assembleDebug
```

Manual test:

- enable blocking
- open YouTube Shorts
- scroll 3 times
- cooldown overlay appears
- overlay blocks taps/swipes on Shorts
- timer counts down
- Android back/home still works
- after cooldown ends, overlay disappears
- session count resets to 0
- dashboard stats update

### Stop condition

Stop after blocking works.

## Phase 5: Polish and reliability pass

### Goal

Make it feel smooth and shippable for personal use.

### Build

Polish:

- typography
- spacing
- dark theme
- large readable cards
- smoother warning fade
- clean empty states
- no ugly debug UI in release builds
- readable settings
- no unnecessary permissions
- no network permission

### Test

Run:

```bash
./gradlew assembleDebug
```

Manual test checklist:

- fresh install
- permission not granted state
- permission granted state
- YouTube not installed state if practical
- blocking toggle on/off
- hard limit changes
- cooldown changes
- YouTube home
- normal YouTube video
- YouTube Shorts
- rapid scrolling
- cooldown
- app restart during cooldown
- phone lock/unlock during cooldown
- service disabled during cooldown

### Stop condition

Stop when the app is stable on the test phone.

## Phase 6: Optional later expansion

Do not build this in v1.

Possible future work:

- Instagram Reels
- better stats
- daily reset schedule
- weekly dashboard
- custom warning style
- whitelist windows
- export local stats
- real launcher mode

## OpenCode prompt for Phase 1

Use after Phase 0 passes:

```text
Read docs/README.md, docs/MVP_SPEC.md, and docs/PHASED_BUILD_AND_TEST_PLAN.md.

Continue with Phase 1 only.

Implement the AccessibilityService skeleton for Motherboard:
- manifest declaration
- accessibility service XML config
- MotherboardAccessibilityService class
- YouTube-focused event handling
- logcat debug logging with tag MotherboardA11y
- dashboard permission/service status

Do not implement Shorts detection yet.
Do not implement counting yet.
Do not implement overlays yet.
Do not request INTERNET permission.

Run ./gradlew assembleDebug.
Summarize files changed and whether the build passed.
Stop after Phase 1.
```

## OpenCode prompt for Phase 2

```text
Read docs/README.md, docs/MVP_SPEC.md, and docs/PHASED_BUILD_AND_TEST_PLAN.md.

Continue with Phase 2 only.

Implement simple YouTube Shorts detection:
- create YouTubeShortsDetector
- inspect active accessibility window nodes
- detect likely Shorts screen using simple markers
- expose current detection state to dashboard
- log why Shorts was or was not detected

Do not count scrolls yet.
Do not show overlays yet.
Do not block anything yet.

Run ./gradlew assembleDebug.
Summarize files changed and whether the build passed.
Stop after Phase 2.
```

## OpenCode prompt for Phase 3

```text
Read docs/README.md, docs/MVP_SPEC.md, and docs/PHASED_BUILD_AND_TEST_PLAN.md.

Continue with Phase 3 only.

Implement Shorts scroll counting and warning overlay:
- increment session count only on debounced TYPE_VIEW_SCROLLED events while YouTubeShortsDetector says YouTubeShorts
- persist session count with DataStore
- show a 2-second warning overlay after each counted scroll
- warning shows "Shorts: X / limit"
- warning uses green, orange, or red severity
- no blocking overlay yet

Run ./gradlew assembleDebug.
Summarize files changed and whether the build passed.
Stop after Phase 3.
```

## OpenCode prompt for Phase 4

```text
Read docs/README.md, docs/MVP_SPEC.md, and docs/PHASED_BUILD_AND_TEST_PLAN.md.

Continue with Phase 4 only.

Implement cooldown blocking:
- when session count reaches hard limit, show blocking overlay
- overlay displays cooldown timer
- overlay blocks Shorts interaction but keeps Android navigation usable
- after cooldown ends, remove overlay and reset session count
- update local stats

Run ./gradlew assembleDebug.
Summarize files changed and whether the build passed.
Stop after Phase 4.
```

## OpenCode prompt for Phase 5

```text
Read docs/README.md, docs/MVP_SPEC.md, and docs/PHASED_BUILD_AND_TEST_PLAN.md.

Continue with Phase 5 only.

Polish the app:
- clean indie dashboard styling
- big readable typography
- dark theme
- rounded cards
- simple settings
- smooth overlay behavior
- remove noisy debug UI from release behavior
- verify no INTERNET permission
- verify app name is Motherboard

Run ./gradlew assembleDebug.
Summarize files changed and whether the build passed.
Stop after Phase 5.
```
