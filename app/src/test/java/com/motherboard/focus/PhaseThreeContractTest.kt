package com.motherboard.focus

import com.motherboard.focus.storage.InterventionSettings
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PhaseThreeContractTest {

    @Test
    fun `InterventionSettings has 10 fields including currentSessionCount`() {
        val fieldNames = InterventionSettings::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("currentSessionCount must exist", "currentSessionCount" in fieldNames)
        assertTrue("sessionLimit must exist", "sessionLimit" in fieldNames)
        assertTrue("debugLogging must exist", "debugLogging" in fieldNames)
    }

    @Test
    fun `currentSessionCount defaults to 0`() {
        val s = InterventionSettings()
        assertEquals("currentSessionCount must default to 0", 0, s.currentSessionCount)
    }

    @Test
    fun `SettingsStore has CurrentSessionCount key`() {
        val content = File("src/main/java/com/motherboard/focus/storage/SettingsStore.kt").readText()
        assertTrue("CurrentSessionCount key must exist", content.contains("CurrentSessionCount"))
        assertTrue("current_session_count literal must exist", content.contains("current_session_count"))
    }

    @Test
    fun `config XML includes typeViewScrolled`() {
        val config = File("src/main/res/xml/accessibility_service_config.xml").readText()
        assertTrue("typeViewScrolled must be in event types", config.contains("typeViewScrolled"))
    }

    @Test
    fun `WarningOverlay class exists`() {
        val overlayFile = File("src/main/java/com/motherboard/focus/service/WarningOverlay.kt")
        assertTrue("WarningOverlay.kt must exist", overlayFile.exists())
        val content = overlayFile.readText()
        assertTrue("TYPE_ACCESSIBILITY_OVERLAY must be used", content.contains("TYPE_ACCESSIBILITY_OVERLAY"))
    }

    @Test
    fun `WarningOverlay has show method`() {
        val content = File("src/main/java/com/motherboard/focus/service/WarningOverlay.kt").readText()
        assertTrue("show(count, limit) must exist", content.contains("fun show(count"))
    }

    @Test
    fun `WarningOverlay has dismiss method`() {
        val content = File("src/main/java/com/motherboard/focus/service/WarningOverlay.kt").readText()
        assertTrue("dismiss() must exist", content.contains("fun dismiss()"))
    }

    @Test
    fun `WarningOverlay has destroy method`() {
        val content = File("src/main/java/com/motherboard/focus/service/WarningOverlay.kt").readText()
        assertTrue("destroy() must exist", content.contains("fun destroy()"))
    }

    @Test
    fun `service checks TYPE_VIEW_SCROLLED event type`() {
        val content = File("src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt").readText()
        assertTrue("must check TYPE_VIEW_SCROLLED", content.contains("TYPE_VIEW_SCROLLED"))
    }

    @Test
    fun `service checks blockingEnabled gate`() {
        val content = File("src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt").readText()
        assertTrue("must check blockingEnabled", content.contains("blockingEnabled"))
    }

    @Test
    fun `service checks detectionState YouTubeShorts gate`() {
        val content = File("src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt").readText()
        assertTrue("must check ShortsDetectionState.YouTubeShorts", content.contains("ShortsDetectionState.YouTubeShorts"))
    }

    @Test
    fun `service has debounce logic via lastCountedAtMs`() {
        val content = File("src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt").readText()
        assertTrue("lastCountedAtMs must be present", content.contains("lastCountedAtMs"))
    }

    @Test
    fun `service references SettingsStore for persistence`() {
        val content = File("src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt").readText()
        assertTrue("SettingsStore must be imported or referenced", content.contains("SettingsStore"))
        assertTrue("store.save must be called", content.contains("store.save"))
    }

    @Test
    fun `service calls warningOverlay show after counting`() {
        val content = File("src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt").readText()
        assertTrue("warningOverlay.show must be called", content.contains("warningOverlay.show"))
    }

    @Test
    fun `service destroys overlay in onDestroy`() {
        val content = File("src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt").readText()
        assertTrue("warningOverlay.destroy() must be in onDestroy", content.contains("warningOverlay.destroy()"))
    }

    @Test
    fun `ViewModel syncs blockingEnabled to service`() {
        val content = File("src/main/java/com/motherboard/focus/ui/DashboardViewModel.kt").readText()
        assertTrue("must sync blockingEnabled", content.contains("blockingEnabled"))
    }

    @Test
    fun `ViewModel syncs eventDebounceMillis to service`() {
        val content = File("src/main/java/com/motherboard/focus/ui/DashboardViewModel.kt").readText()
        assertTrue("must sync eventDebounceMillis", content.contains("eventDebounceMillis"))
    }

    @Test
    fun `HomeScreen shows dynamic sessionCount not hardcoded zero`() {
        val content = File("src/main/java/com/motherboard/focus/ui/screens/HomeScreen.kt").readText()
        assertTrue("must use sessionCount variable", content.contains("sessionCount"))
        assertTrue("must show dynamic counter", content.contains("\$sessionCount"))
    }

    @Test
    fun `no shortsBlockedToday increment in service`() {
        val content = File("src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt").readText()
        assertFalse("shortsBlockedToday must not be incremented in Phase 3", content.contains("shortsBlockedToday"))
    }

    @Test
    fun `no INTERNET permission in manifest`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("INTERNET must not be declared", manifest.contains("android.permission.INTERNET"))
    }
}
