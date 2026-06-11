# Phase 2 Learnings

## 2026-06-11: YouTubeShortsDetector Created

- Created `YouTubeShortsDetector.kt` in service package.
- Uses `ShortsDetectionState` enum: `NotYouTube`, `YouTubeNotShorts`, `YouTubeShorts`.
- Companion `MutableStateFlow<ShortsDetectionState>` for UI observation.
- Retry pattern: `Handler.postDelayed`, generation token, `300L` × `3` retries.
- `scheduleRetryOrFail()` helper handles both null root and no-match cases.
- Debounce: `750L` `DETECTION_DEBOUNCE_MS` via `SystemClock.uptimeMillis()`.
- Node recycling in `finally` block (`root.recycle()`) and `forEach` loop.
- Fully qualified YouTube Shorts view IDs: `reel_player_page_container`, `reel_recycler`, `reel_progress_bar`.
- Tag convention: `"MotherboardA11y"`.
- File verified at 174 lines, all key patterns match.
