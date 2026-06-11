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
    fun `InterventionSettings still has all 9 expected fields after Phase 2`() {
        val fieldNames = com.motherboard.focus.storage.InterventionSettings::class.java.declaredFields.map { it.name }.toSet()
        // Check each expected field is present (avoid exact count — Kotlin compiler adds synthetic fields)
        assertTrue("sessionLimit must still exist", "sessionLimit" in fieldNames)
        assertTrue("cooldownDurationMillis must still exist", "cooldownDurationMillis" in fieldNames)
        assertTrue("warningDurationMillis must still exist", "warningDurationMillis" in fieldNames)
        assertTrue("eventDebounceMillis must still exist", "eventDebounceMillis" in fieldNames)
        assertTrue("youtubeShortsEnabled must still exist", "youtubeShortsEnabled" in fieldNames)
        assertTrue("blockingEnabled must still exist", "blockingEnabled" in fieldNames)
        assertTrue("shortsBlockedToday must still exist", "shortsBlockedToday" in fieldNames)
        assertTrue("cooldownsTriggeredToday must still exist", "cooldownsTriggeredToday" in fieldNames)
        assertTrue("debugLogging must still exist", "debugLogging" in fieldNames)
    }

    @Test
    fun `no INTERNET permission in manifest`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("INTERNET must not be declared", manifest.contains("android.permission.INTERNET"))
    }
}
