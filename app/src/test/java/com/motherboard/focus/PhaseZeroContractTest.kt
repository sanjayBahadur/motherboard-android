package com.motherboard.focus

import com.motherboard.focus.storage.InterventionSettings
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PhaseZeroContractTest {

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
        assertEquals(10, InterventionSettings().sessionLimit)
    }

    @Test
    fun `manifest has no INTERNET permission and service is declared`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("INTERNET permission must not be requested", manifest.contains("android.permission.INTERNET"))
        assertTrue("MotherboardAccessibilityService must be declared in Phase 1", manifest.contains("MotherboardAccessibilityService"))
    }

    @Test
    fun `manifest now declares AccessibilityService for Phase 1`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("AccessibilityService must be declared in Phase 1", manifest.contains("MotherboardAccessibilityService"))
        assertTrue("BIND_ACCESSIBILITY_SERVICE permission required", manifest.contains("BIND_ACCESSIBILITY_SERVICE"))
        assertTrue("accessibility_service_config must be referenced", manifest.contains("accessibility_service_config"))
    }

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
