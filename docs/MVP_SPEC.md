# Motherboard MVP Spec

## App name

**Motherboard**

## MVP target

Block YouTube Shorts after the user scrolls through too many Shorts in one session.

## Supported app in v1

Only:

```text
com.google.android.youtube
```

Do not implement Instagram Reels yet.

## Main dashboard

The dashboard should feel clean, indie, and simple.

It should look closer to a small focused utility app than a corporate settings page. Big readable text, rounded cards, clear status, no clutter. Think "Ascent-like dashboard" in spirit, not a visual copy.

### Dashboard content

Show:

1. app title: `Motherboard`
2. subtitle: `Shorts blocker for your attention span`
3. main status card:
   - `YouTube Shorts Blocking`
   - ON / OFF toggle
4. current session card:
   - big number: `0 / 10`
   - label: `Shorts this session`
5. stats card:
   - `Shorts blocked today`
   - `Cooldowns triggered today`
6. settings card:
   - hard limit for Shorts per session
   - cooldown length in minutes
7. permission card:
   - Accessibility permission status
   - button to open Android Accessibility settings when disabled

### Default settings

```text
YouTube Shorts blocking: ON
Hard limit: 10 Shorts
Cooldown: 5 minutes
Warning overlay duration: 2 seconds
Scroll debounce: 1000 ms
```

### Settings range

Hard limit:

```text
min: 3
max: 50
default: 10
```

Cooldown:

```text
min: 1 minute
max: 30 minutes
default: 5 minutes
```

## Detection behavior

Motherboard does not need to identify the exact video.

Motherboard does not need to predict that a new Short loaded.

Motherboard only needs simple detection:

```text
If foreground app is YouTube
AND current screen is likely YouTube Shorts
AND a scroll event happens
AND debounce window has passed
THEN increment current session count by 1
```

### Foreground app detection

Use AccessibilityService events. Only care about:

```text
com.google.android.youtube
```

Ignore other packages in v1.

### Shorts screen detection

Use simple AccessibilityService node inspection.

The implementation should have a small `YouTubeShortsDetector` class that checks the active window for simple markers.

The first version can use markers such as:

- visible text or content description containing `Shorts`
- YouTube bottom navigation or selected tab hints
- known resource names found during manual testing
- event class names or source node context that clearly appear only while on Shorts

The detector must be easy to update when YouTube changes UI.

Do not use OCR.  
Do not use screenshots.  
Do not use image recognition.  
Do not use AI.

### Counting rule

Increment the session counter when:

1. blocking is enabled
2. AccessibilityService is enabled
3. package is YouTube
4. Shorts screen is detected
5. event type is a useful scroll/content event
6. debounce window has passed

Recommended event types to start with:

```text
TYPE_VIEW_SCROLLED
TYPE_WINDOW_CONTENT_CHANGED
TYPE_WINDOW_STATE_CHANGED
```

Only `TYPE_VIEW_SCROLLED` should increment the count at first.

The other event types can update detection state but should not increment count unless manual testing proves they are needed.

### Debounce

Use a simple timestamp debounce.

Default:

```text
1000 ms
```

This prevents one swipe from counting five times because Android accessibility events apparently reproduce like bacteria.

## Warning overlay

Before blocking, show a warning overlay for 2 seconds after count increments.

The warning is not interactive.

The warning should be visually obvious but not obnoxious.

### Warning colors

Use three levels:

```text
green: low usage
orange: near limit
red: at final warning before block
```

Suggested thresholds:

```text
green: count < 50% of limit
orange: count >= 50% and count < 90% of limit
red: count >= 90% of limit
```

### Warning content

Show:

```text
Shorts: 7 / 10
```

Do not rely on color only.

### Warning design

Use one of these simple designs:

1. soft transparent full-screen tint
2. thick transparent border around the screen
3. top floating pill

Start with the **top floating pill** or **screen border** because it is less annoying and easier to test.

Avoid aggressive blinking in v1. A short fade in/out is enough.

## Blocking overlay

When session count reaches the hard limit:

1. show a blocking overlay on top of YouTube Shorts
2. display the cooldown timer
3. prevent interaction with Shorts while the timer runs
4. allow Android system navigation to remain usable
5. remove overlay when cooldown ends
6. reset current session count to zero after cooldown

### Blocking copy

Use simple copy:

```text
Cooldown
You hit your Shorts limit.
Back in 04:59
```

Button:

```text
Leave YouTube
```

The button can send the user to Android home screen if practical. If that is annoying to implement, omit the button in v1.

## Stats

Track locally:

```text
current_session_count
shorts_blocked_today
cooldowns_triggered_today
last_cooldown_started_at
blocking_enabled
hard_limit
cooldown_minutes
```

Use DataStore Preferences.

No account.  
No cloud sync.  
No analytics.

## Accessibility permission screen

The app must clearly explain why accessibility permission is needed.

Suggested copy:

```text
Motherboard uses Android Accessibility permission to detect when YouTube Shorts is open, count Shorts scrolls, and show a cooldown overlay when your limit is reached.

Motherboard does not read messages, store screen text, take screenshots, or send data anywhere.
```

## What is possible

This app can reasonably do:

- detect YouTube as the foreground app
- detect likely YouTube Shorts screens
- count scroll events while Shorts is active
- show warning overlays
- show blocking cooldown overlays
- store local settings and stats
- work without internet

## What is not guaranteed

This app cannot guarantee:

- exact count of every unique Short watched
- permanent compatibility with every YouTube UI update
- blocking if AccessibilityService is disabled
- blocking if YouTube changes its accessibility labels
- bypass-proof enforcement
- iOS support

## Quality bar

The app should feel smooth.

No laggy overlays.  
No flickering UI.  
No huge settings mess.  
No random background work.  
No mysterious battery drain.

If detection is uncertain, prefer not counting over false blocking.

False negatives are annoying.  
False positives make users uninstall the app and question your bloodline.
