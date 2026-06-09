# Motherboard Android MVP

This is the source of truth for the first working version of **Motherboard**.

Repository name: `motherboard-android`

The old larger doc clusters are deprecated. Do not use them as implementation authority. This project is not called ReelGuard. This project is **Motherboard**.

## What we are building

Motherboard is a tiny Android app that helps users stop binge-scrolling YouTube Shorts.

The first version supports **YouTube Shorts only**.

It will:

1. use Android AccessibilityService to detect when the user is on YouTube Shorts
2. count Shorts scroll events during the current session
3. show a short warning overlay as the user approaches the hard limit
4. block the Shorts surface when the hard limit is reached
5. show a cooldown timer
6. reset the session after cooldown
7. show a clean dashboard with stats and settings

No AI.  
No backend.  
No account system.  
No analytics.  
No OCR.  
No screenshots.  
No launcher behavior in v1.  
No Instagram Reels in v1.

## Required docs

Read only these files:

1. `README.md`
2. `MVP_SPEC.md`
3. `PHASED_BUILD_AND_TEST_PLAN.md`

Do not create extra architecture documents unless the user asks.

## Product principle

Keep the app stupid simple.

The app does not need to know which exact Short is playing. It only needs to know:

```text
User is probably inside YouTube Shorts
+
a Shorts scroll event happened
+
the debounce window passed
=
count +1
```

That is the core.

## Technical baseline

Use:

- Kotlin
- Jetpack Compose
- AccessibilityService
- DataStore Preferences
- native Android overlay views from the accessibility service
- local-only storage

Do not request `INTERNET`.

## First OpenCode / Sisyphus prompt

Use this first:

```text
You are working in the motherboard-android repo.

The source of truth is only:

1. docs/README.md
2. docs/MVP_SPEC.md
3. docs/PHASED_BUILD_AND_TEST_PLAN.md

The app is called Motherboard.

Build Phase 0 only:
- create or verify the Android project skeleton
- Kotlin
- Jetpack Compose
- simple dashboard UI
- settings state model
- DataStore Preferences setup
- no AccessibilityService implementation yet
- no overlays yet
- no network permission
- no analytics
- no backend

After Phase 0:
- run ./gradlew assembleDebug
- report files changed
- report whether the build passed
- stop
```

## Non-negotiable rules for the coding agent

- Do not rename the app.
- Do not add AI.
- Do not add cloud sync.
- Do not add a launcher.
- Do not add Instagram support in v1.
- Do not build a giant architecture.
- Do not store raw accessibility text.
- Do not request network permissions.
- Do not claim perfect Shorts counting.
- Build one small phase at a time and test after every phase.
